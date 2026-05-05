import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx,vue}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'SF Pro Display', 'PingFang SC', 'Microsoft YaHei', 'sans-serif']
      },
      colors: {
        page: '#F5F5F7',
        ink: '#1D1D1F',
        muted: '#6E6E73',
        hairline: '#E5E5EA',
        apple: '#007AFF',
        warn: '#FF9500',
        risk: '#FF3B30',
        done: '#34C759'
      },
      boxShadow: {
        panel: '0 18px 60px rgba(0, 0, 0, 0.08)'
      },
      transitionTimingFunction: {
        apple: 'cubic-bezier(0.22, 1, 0.36, 1)'
      }
    }
  },
  plugins: []
} satisfies Config;
