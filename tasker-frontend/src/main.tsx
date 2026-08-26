import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import { bootCatalogReady } from './i18n'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthContext'
import { ErrorToastStack } from './components/ErrorToast'

// Mount once the boot language's catalog is in place, so the first paint is already in the right
// language and direction. Resolved immediately for English; see `bootCatalogReady`.
void bootCatalogReady.then(() => {
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
})
