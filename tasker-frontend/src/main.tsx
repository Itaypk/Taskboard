import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import './i18n'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthContext'
import { ErrorToastStack } from './components/ErrorToast'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
        <ErrorToastStack />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
