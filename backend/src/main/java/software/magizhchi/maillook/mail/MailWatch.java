package software.magizhchi.maillook.mail;

import jakarta.mail.Folder;
import jakarta.mail.Store;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import jakarta.annotation.PreDestroy;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.AccountRepo;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * New mail within seconds, not at the next 3-minute sync. Each mailbox's Inbox is held open with IMAP IDLE, so the
 * server (Gmail, Hostinger, Outlook…) says the moment mail arrives, and that mailbox syncs at once: an OTP shows in
 * MailLook, and rings, about as fast as in Gmail. The timed sync stays as the safety net.
 */
@Service
public class MailWatch {

    private static final Logger log = LoggerFactory.getLogger(MailWatch.class);
    /** Servers drop an IDLE that runs too long (Gmail after ~29 minutes); it's renewed well before. */
    private static final long RENEW_MINUTES = 9;
    private static final int MAX_WATCHERS = 500;

    private final AccountRepo accounts;
    private final ImapClient imap;
    private final SyncService sync;
    private final boolean enabled;
    private final Map<Long, Watcher> watchers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renew = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mail-watch-renew");
        t.setDaemon(true);
        return t;
    });

    public MailWatch(AccountRepo accounts, ImapClient imap, SyncService sync,
                     @Value("${maillook.sync.idle:true}") boolean enabled) {
        this.accounts = accounts;
        this.imap = imap;
        this.sync = sync;
        this.enabled = enabled;
    }

    /** Keeps one watcher per mailbox: starts new ones, stops those whose mailbox is gone. */
    @Scheduled(initialDelay = 20_000, fixedDelay = 60_000)
    public void refresh() {
        if (!enabled) return;
        Set<Long> live = new HashSet<>();
        for (Account a : accounts.all()) {
            live.add(a.id());
            if (!watchers.containsKey(a.id()) && watchers.size() < MAX_WATCHERS) {
                Watcher w = new Watcher(a.id());
                watchers.put(a.id(), w);
                w.start();
            }
        }
        watchers.forEach((id, w) -> {
            if (!live.contains(id)) {
                w.stop();
                watchers.remove(id);
            }
        });
    }

    /** How many mailboxes are being watched right now (for tests and the logs). */
    public int watching() {
        return (int) watchers.values().stream().filter(w -> w.idling).count();
    }

    @PreDestroy
    public void shutdown() {
        watchers.values().forEach(Watcher::stop);
        renew.shutdownNow();
    }

    private final class Watcher implements Runnable {
        private final long accountId;
        private final Thread thread;
        private volatile boolean stopped;
        private volatile boolean idling;
        private volatile Store store;

        Watcher(long accountId) {
            this.accountId = accountId;
            this.thread = new Thread(this, "mail-watch-" + accountId);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stop() {
            stopped = true;
            ImapClient.closeQuietly(store);
            thread.interrupt();
        }

        @Override
        public void run() {
            long wait = 30_000;
            while (!stopped) {
                Account a = accounts.find(accountId).orElse(null);
                if (a == null) return;
                // A mailbox whose password is refused waits for the timed sync to get back in; no hammering.
                if ("error".equals(a.status())) {
                    sleep(60_000);
                    continue;
                }
                IMAPFolder inbox = null;
                java.util.concurrent.ScheduledFuture<?> renewal = null;
                try {
                    store = imap.connect(a);
                    inbox = (IMAPFolder) store.getFolder("INBOX");
                    inbox.open(Folder.READ_ONLY);
                    inbox.addMessageCountListener(new MessageCountAdapter() {
                        @Override
                        public void messagesAdded(MessageCountEvent e) {
                            sync.request(accountId);
                        }
                    });
                    // Anything that came in while this mailbox wasn't watched.
                    sync.request(accountId);
                    IMAPFolder held = inbox;
                    // Any command from another thread ends the current IDLE; the loop then starts a fresh one.
                    renewal = renew.scheduleAtFixedRate(() -> {
                        try {
                            held.getMessageCount();
                        } catch (Exception ignored) {
                            // the watcher notices on its own
                        }
                    }, RENEW_MINUTES, RENEW_MINUTES, TimeUnit.MINUTES);
                    wait = 30_000;
                    while (!stopped && inbox.isOpen()) {
                        idling = true;
                        inbox.idle(true);
                    }
                } catch (Exception e) {
                    if (stopped) return;
                    log.info("Watching the Inbox of account {} stopped ({}); trying again in {} s", accountId, e.toString(), wait / 1000);
                } finally {
                    idling = false;
                    if (renewal != null) renewal.cancel(false);
                    ImapClient.closeQuietly(inbox, false);
                    ImapClient.closeQuietly(store);
                    store = null;
                }
                if (stopped) return;
                sleep(wait);
                wait = Math.min(wait * 2, 5 * 60_000);
            }
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            }
        }
    }
}
