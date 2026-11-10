// Small stroke icons (24px grid, currentColor).
function Svg({ children, size = 18, className = '', ...rest }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={className} {...rest}>
      {children}
    </svg>
  )
}

export const IconCompose = (p) => <Svg {...p}><path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" /></Svg>
export const IconInbox = (p) => <Svg {...p}><path d="M22 12h-6l-2 3h-4l-2-3H2" /><path d="M5.5 5h13L22 12v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6Z" /></Svg>
export const IconHand = (p) => <Svg {...p}><path d="M18 11V6a2 2 0 0 0-4 0v5" /><path d="M14 10V4a2 2 0 0 0-4 0v6" /><path d="M10 10.5V6a2 2 0 0 0-4 0v8" /><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.9-6-2.4l-3.6-3.6a2 2 0 0 1 2.8-2.8L7 15" /></Svg>
export const IconInfo = (p) => <Svg {...p}><circle cx="12" cy="12" r="10" /><path d="M12 16v-4M12 8h.01" /></Svg>
export const IconClock = (p) => <Svg {...p}><circle cx="12" cy="12" r="10" /><path d="M12 6v6l4 2" /></Svg>
export const IconSend = (p) => <Svg {...p}><path d="m22 2-7 20-4-9-9-4Z" /><path d="M22 2 11 13" /></Svg>
export const IconArchive = (p) => <Svg {...p}><rect x="2" y="3" width="20" height="5" rx="1" /><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8M10 12h4" /></Svg>
export const IconNews = (p) => <Svg {...p}><path d="M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-2 2Zm0 0a2 2 0 0 1-2-2v-9c0-1.1.9-2 2-2h2" /><path d="M18 14h-8M15 18h-5M10 6h8v4h-8Z" /></Svg>
export const IconUsers = (p) => <Svg {...p}><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8" /></Svg>
export const IconSettings = (p) => <Svg {...p}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z" /></Svg>
export const IconRefresh = (p) => <Svg {...p}><path d="M21 12a9 9 0 0 1-15.5 6.2L3 16" /><path d="M3 12a9 9 0 0 1 15.5-6.2L21 8" /><path d="M21 3v5h-5M3 21v-5h5" /></Svg>
export const IconSearch = (p) => <Svg {...p}><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></Svg>
export const IconCheck = (p) => <Svg {...p}><path d="M20 6 9 17l-5-5" /></Svg>
export const IconTrash = (p) => <Svg {...p}><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /></Svg>
export const IconFlag = ({ filled, ...p }) => <Svg {...p}><path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1Z" fill={filled ? 'currentColor' : 'none'} /><path d="M4 22v-7" /></Svg>
export const IconLanes = (p) => <Svg {...p}><path d="M3 6h18M3 12h12M3 18h6" /></Svg>
export const IconReply = (p) => <Svg {...p}><path d="M9 17 4 12l5-5" /><path d="M20 18v-2a4 4 0 0 0-4-4H4" /></Svg>
export const IconReplyAll = (p) => <Svg {...p}><path d="M7 17 2 12l5-5" /><path d="M12 17l-5-5 5-5" /><path d="M22 18v-2a4 4 0 0 0-4-4H7" /></Svg>
export const IconForward = (p) => <Svg {...p}><path d="m15 17 5-5-5-5" /><path d="M4 18v-2a4 4 0 0 1 4-4h12" /></Svg>
export const IconTranslate = (p) => <Svg {...p}><path d="m5 8 6 6M4 14l6-6 2-3M2 5h12M7 2h1M22 22l-5-10-5 10M14 18h6" /></Svg>
export const IconInstall = (p) => <Svg {...p}><rect x="5" y="2" width="14" height="20" rx="2.5" /><path d="M12 7v7M9 11l3 3 3-3M10 18h4" /></Svg>
export const IconPaperclip = (p) => <Svg {...p}><path d="m21.4 11.1-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5" /></Svg>
export const IconMic = (p) => <Svg {...p}><rect x="9" y="2" width="6" height="12" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v4M8 22h8" /></Svg>
export const IconSparkle = (p) => <Svg {...p}><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9Z" /><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8Z" /></Svg>
export const IconX = (p) => <Svg {...p}><path d="M18 6 6 18M6 6l12 12" /></Svg>
export const IconMenu = (p) => <Svg {...p}><path d="M3 6h18M3 12h18M3 18h18" /></Svg>
export const IconBack = (p) => <Svg {...p}><path d="m15 18-6-6 6-6" /></Svg>
export const IconChevron = ({ open, ...p }) => <Svg {...p} style={{ transform: open ? 'rotate(90deg)' : 'none', transition: 'transform .2s' }}><path d="m9 18 6-6-6-6" /></Svg>
export const IconEye = (p) => <Svg {...p}><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" /></Svg>
export const IconDownload = (p) => <Svg {...p}><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" /></Svg>
export const IconUnsub = (p) => <Svg {...p}><path d="M4 4h16v12H4z" /><path d="m4 4 8 7 8-7" /><path d="M15 20h6" /></Svg>
export const IconLogout = (p) => <Svg {...p}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" /></Svg>
export const IconPlus = (p) => <Svg {...p}><path d="M12 5v14M5 12h14" /></Svg>
export const IconAlert = (p) => <Svg {...p}><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" /><path d="M12 9v4M12 17h.01" /></Svg>
export const IconMore = (p) => <Svg {...p}><circle cx="12" cy="5" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="12" cy="19" r="1" /></Svg>
export const IconCloudUp = (p) => <Svg {...p}><path d="M7 18a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18 10a4 4 0 0 1 0 8" /><path d="m9 15 3-3 3 3M12 12v8" /></Svg>
export const IconCloudCheck = (p) => <Svg {...p}><path d="M7 18a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18 10a4 4 0 0 1-.5 7.97" /><path d="m9 15 2.2 2.2L15.5 13" /></Svg>
export const IconVideo = (p) => <Svg {...p}><rect x="2" y="6" width="14" height="12" rx="2" /><path d="m16 10 6-3.5v11L16 14" /></Svg>
export const IconCalendar = (p) => <Svg {...p}><rect x="3" y="4" width="18" height="18" rx="2" /><path d="M16 2v4M8 2v4M3 10h18" /></Svg>
export const IconFolder = (p) => <Svg {...p}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" /></Svg>
export const IconSpam = (p) => <Svg {...p}><path d="M12 3 4 6v6c0 4.5 3.4 8 8 9 4.6-1 8-4.5 8-9V6Z" /><path d="M12 8v5" /><path d="M12 16.5v.01" /></Svg>
export const IconDraft = (p) => <Svg {...p}><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z" /><path d="M14 3v6h6" /><path d="M8 17l1-3 5-5 2 2-5 5Z" /></Svg>
export const IconMove = (p) => <Svg {...p}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" /><path d="M10 13h6" /><path d="m13.5 10.5 2.5 2.5-2.5 2.5" /></Svg>
export const IconSnooze = (p) => <Svg {...p}><circle cx="12" cy="13" r="8" /><path d="M5 3 2 6M22 6l-3-3M9.5 10h5l-5 6h5" /></Svg>
export const IconNote = (p) => <Svg {...p}><path d="M5 3h10l4 4v14H5Z" /><path d="M15 3v4h4M8.5 12h7M8.5 16h5" /></Svg>
export const IconBell = (p) => <Svg {...p}><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0" /></Svg>
export const IconBook = (p) => <Svg {...p}><path d="M5 4a2 2 0 0 1 2-2h12v18H7a2 2 0 0 0-2 2Z" /><path d="M5 20V4" /><circle cx="12.5" cy="9" r="2.5" /><path d="M8.5 15.5a4 4 0 0 1 8 0" /></Svg>
export const IconPhone = (p) => <Svg {...p}><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2Z" /></Svg>
export const IconUserPlus = (p) => <Svg {...p}><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M19 8v6M22 11h-6" /></Svg>
export const IconForm = (p) => <Svg {...p}><rect x="4" y="3" width="16" height="18" rx="2" /><path d="M8 8h8M8 12h8M8 16h5" /><path d="m15 16 1.2 1.2L18.5 15" /></Svg>
export const IconReceipt = (p) => <Svg {...p}><path d="M5 2h14v20l-2.3-1.5L14.3 22 12 20.5 9.7 22l-2.4-1.5L5 22Z" /><path d="M9 7h6M9 11h6M9 15h4" /></Svg>
