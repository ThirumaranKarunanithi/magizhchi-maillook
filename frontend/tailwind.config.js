/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // Same palette as Magizhchi Forms: light steps are warm pearl, dark steps are peacock ink.
        slate: { 50: '#fbf8f2', 100: '#f5efe4', 200: '#ebe2d2', 300: '#d9ccb5', 400: '#587579', 500: '#4f6d71', 600: '#3e5b60', 700: '#2d4a4f', 800: '#1d3a40', 900: '#0b3a42' },
        brand: {
          primary: '#B45309', // marigold, deepened for text
          dark: '#92400E',
          gold: '#F5B320',
          ink: '#0B3A42', // peacock ink
          ivory: '#FFFDF8',
          peacock: '#0B5563',
        },
        peacock: { 50: '#ecf6f5', 100: '#d3ebe8', 200: '#a9d6d1', 400: '#3f9a9f', 500: '#0e7c86', 600: '#0b6b74', 700: '#0b5563', 800: '#0a4650' },
      },
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'system-ui', '"Segoe UI"', '"Noto Sans Tamil"', '"Nirmala UI"', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
