        const basePath = window.location.pathname.replace(/\/$/, '');
        if (basePath) {
            document.querySelectorAll('link[rel="manifest"], link[rel="icon"], link[rel="apple-touch-icon"]').forEach(el => {
                const href = el.getAttribute('href');
                if (href && href.startsWith('/')) {
                    el.setAttribute('href', basePath + href);
                }
            });
        }
        if ('serviceWorker' in navigator && window.isSecureContext) {
            window.addEventListener('load', () => {
                navigator.serviceWorker.register((basePath || '') + '/sw.js', { scope: (basePath || '') + '/' }).catch(() => {});
            });
        }
