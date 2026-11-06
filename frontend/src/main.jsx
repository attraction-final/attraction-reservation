import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import { AuthProvider } from './context/AuthContext.jsx';
import './styles.css';

// 리액트 앱의 시작점입니다.
// BrowserRouter: 주소(URL)에 따라 다른 화면을 보여 주는 기능
// AuthProvider : 로그인한 사용자 정보를 모든 화면에서 꺼내 쓸 수 있게 해 주는 기능
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </React.StrictMode>
);
