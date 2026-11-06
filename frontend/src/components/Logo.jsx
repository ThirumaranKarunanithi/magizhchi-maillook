// Magizhchi MailLook brand: peacock tile, ivory envelope whose flap is a marigold smile, and a
// marigold sun peeking over the corner. Wordmark is live text so it uses the app font everywhere.
export default function Logo({ size = 36, withName = true, tagline = true, large = false, className = '' }) {
  return (
    <div className={`flex items-center ${large ? 'gap-3.5' : 'gap-2'} ${className}`}>
      <img src="/brand/maillook-mark.svg" alt="" width={size} height={size} className="shrink-0" />
      {withName && (
        <div className="leading-none text-left">
          <div className={`${large ? 'text-[15px]' : 'text-[10.5px]'} font-semibold tracking-wide text-slate-500`}>Magizhchi</div>
          <div className={`${large ? 'mt-1 text-[34px]' : 'mt-0.5 text-[19px]'} font-extrabold tracking-tight text-brand-ink`}>
            Mail<span className="text-[#D97706]">Look</span>
          </div>
          {tagline && !large && <div className="mt-1 text-[10.5px] font-medium text-slate-500">the mail app that makes you happy again</div>}
        </div>
      )}
    </div>
  )
}
