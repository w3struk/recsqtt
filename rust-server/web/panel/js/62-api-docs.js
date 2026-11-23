        function updateApiDocsPaths() {
            const basePath = window.location.pathname.replace(/\/$/, '');
            const baseUrl = window.location.origin + (basePath || '');
            const baseEl = document.getElementById('apiBaseUrlDisplay');
            if (baseEl) baseEl.textContent = baseUrl;
            document.querySelectorAll('#apiDocsDlg .api-path').forEach(el => {
                if (!el.dataset.orig) el.dataset.orig = el.textContent.trim();
                const orig = el.dataset.orig;
                el.textContent = orig.split(' и ').map(p => {
                    const cleanP = p.trim();
                    if (cleanP.startsWith('/{') || cleanP.startsWith('/api/sub')) {
                        return cleanP;
                    }
                    return (basePath || '') + cleanP;
                }).join(' и ');
            });
            document.querySelectorAll('#apiDocsDlg code').forEach(el => {
                if (!el.dataset.orig) el.dataset.orig = el.textContent;
                let text = el.dataset.orig;
                if (text.includes('/api/') && !text.includes('/api/sub')) {
                    text = text.replaceAll('/api/', (basePath || '') + '/api/');
                }
                el.textContent = text;
            });
        }

        function openApiDocsDlg() {
            switchApiDocsTab('clients');
            updateApiDocsPaths();
            document.getElementById('apiDocsDlg').showModal();
        }

        function switchApiDocsTab(tab) {
            const tabs = ['clients', 'groups', 'subs', 'routing', 'system'];
            tabs.forEach(t => {
                const btn = document.getElementById('btnApiTab' + t.charAt(0).toUpperCase() + t.slice(1));
                const pane = document.getElementById('apiPane' + t.charAt(0).toUpperCase() + t.slice(1));
                if (btn) {
                    if (t === tab) {
                        btn.classList.add('active');
                    } else {
                        btn.classList.remove('active');
                    }
                }
                if (pane) {
                    pane.style.display = (t === tab) ? 'flex' : 'none';
                }
            });
        }

