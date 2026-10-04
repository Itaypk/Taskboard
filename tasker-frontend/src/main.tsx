import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import { bootCatalogReady } from './i18n'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthContext'
import { ToastStack } from './components/ToastStack'
import { capturePrerendered } from './prerendered'

// Mount once the boot language's catalog is in place, so the first paint is already in the right
// language and direction. Resolved immediately for English; see `bootCatalogReady`.
void bootCatalogReady.then(() => {
  const root = document.getElementById('root')!
  capturePrerendered(root)
  createRoot(root).render(
    <StrictMode>
      <BrowserRouter>
        <AuthProvider>
          <App />
          <ToastStack />
        </AuthProvider>
      </BrowserRouter>
    </StrictMode>,
  )
})
