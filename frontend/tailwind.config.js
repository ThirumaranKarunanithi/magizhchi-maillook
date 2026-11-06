/** @type {import('tailwindcss').Config} */
// Magizhchi MailLook "Night grade": the teal-and-orange film grade, which happens to be Magizhchi's own
// peacock and marigold. Shadows sit in deep peacock, highlights are warm ivory, the accent is marigold.
// The colour names the components already use are remapped here, so `bg-white` is a lit surface,
// `slate-50…900` runs from deep shadow to ivory, and the status colours are their night versions.
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        white: '#11292e', // a panel lit from above
        slate: {
          50: '#0b2025', 100: '#11292f', 200: '#1c3a41', 300: '#2b4b53', 400: '#7d9a9d',
          500: '#9cb4b5', 600: '#b9cbcb', 700: '#d4e0de', 800: '#e6eeeb', 900: '#f4f1e8',
        },
        brand: {
          primary: '#ffb547', // marigold, lifted for dark ground
          dark: '#f59e0b',
          gold: '#f5b320',
          ink: '#f4ede0', // warm ivory text
          ivory: '#fffdf8',
          peacock: '#0b5563',
        },
        peacock: {
          50: '#0c3036', 100: '#103e45', 200: '#18545c', 300: '#23707a', 400: '#3fb5bb', 500: '#52c6c9',
          600: '#6dd2d3', 700: '#8fe0de', 800: '#aeeae6', 900: '#d2f5f2',
        },
        red: {
          50: '#2b1517', 100: '#3a1b1d', 200: '#5c2629', 300: '#83393a', 400: '#e2625b', 500: '#f0716a',
          600: '#ff7d73', 700: '#ff9a92', 800: '#ffbbb5', 900: '#ffdcd8',
        },
        emerald: {
          50: '#0d2a22', 100: '#11372c', 200: '#1a5040', 300: '#256b55', 400: '#38b48a', 500: '#43c99a',
          600: '#5fd8ab', 700: '#86e6c2', 800: '#aef0d7', 900: '#d6f8eb',
        },
        amber: {
          50: '#2c2010', 100: '#3b2a13', 200: '#5a3f17', 300: '#7c561b', 400: '#e0a12a', 500: '#f5b320',
          600: '#ffc04d', 700: '#ffcf75', 800: '#ffdf9f', 900: '#fff0cf',
        },
      },
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'system-ui', '"Segoe UI"', '"Noto Sans Tamil"', '"Nirmala UI"', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
