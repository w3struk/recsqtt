        function showToast(text, type = 'success') {
            const container = document.getElementById('toastContainer');
            if (container) {
                const openDialog = Array.from(document.querySelectorAll('dialog[open]')).pop();
                if (openDialog) {
                    openDialog.appendChild(container);
                } else if (container.parentElement !== document.body) {
                    document.body.appendChild(container);
                }
            }
            const toast = document.createElement('div');
            const isError = type === 'error' || type === true;
            const isWarning = type === 'warning';
            toast.className = 'toast ' + (isError ? 'toast-error' : isWarning ? 'toast-warning' : 'toast-success');
            const icon = document.createElement('div');
            icon.className = 'toast-icon';
            if (isError) {
                icon.style.color = 'var(--danger)';
                icon.style.background = 'color-mix(in srgb, var(--danger) 15%, transparent)';
                icon.textContent = '✗';
            } else if (isWarning) {
                icon.style.color = '#f59e0b';
                icon.style.background = 'rgba(245, 158, 11, 0.15)';
                icon.textContent = '⚠';
            } else {
                icon.style.color = 'var(--primary)';
                icon.style.background = 'var(--icon-bg)';
                icon.textContent = '✓';
            }
            const message = document.createElement('span');
            message.textContent = String(text);
            toast.append(icon, message);
            if (container) container.appendChild(toast);
            const duration = isWarning ? 4000 : 3000;
            setTimeout(() => { toast.classList.add('hide'); setTimeout(() => toast.remove(), 300); }, duration);
        }

        function showConfirm(title, message, btnText, btnClass, callback, cancelCallback) {
            const dlg = document.getElementById('confirmDlg');
            const titleEl = document.getElementById('confirmTitle');
            const msgEl = document.getElementById('confirmMessage');
            const btn = document.getElementById('confirmActionBtn');
            const cancelBtn = dlg.querySelector('button.btn-ghost');
            let actionTriggered = false;

            if (titleEl) titleEl.textContent = title || 'Подтверждение';
            if (msgEl) msgEl.textContent = message || '';
            if (btn) {
                btn.textContent = btnText || 'Подтвердить';
                btn.className = `btn ${btnClass || 'btn-primary'}`;
                btn.onclick = () => {
                    actionTriggered = true;
                    dlg.close();
                    if (callback) callback();
                };
            }
            if (cancelBtn) {
                cancelBtn.onclick = () => {
                    dlg.close();
                };
            }
            if (dlg) {
                dlg.onclose = () => {
                    if (!actionTriggered && cancelCallback) {
                        cancelCallback();
                    }
                };
                dlg.showModal();
            }
        }

