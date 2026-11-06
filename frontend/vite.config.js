import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite 설정 파일
// - npm run dev 로 띄운 개발 서버(5173)에서 /api 로 시작하는 요청은
//   스프링부트 서버(8080)로 대신 전달(proxy)합니다.
// - 그래서 개발 중에는 CORS 설정 없이도 백엔드와 통신할 수 있어요.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
