        let logsLoadedOnce = false;
        let logsInFlight = false;
        async function loadLogs() {
            if (logsInFlight) return;
            const consoleElem = document.getElementById('logConsole');

            const selection = window.getSelection();
            const isSelecting = selection && selection.toString().length > 0 && consoleElem.contains(selection.anchorNode);

            if (isSelecting) {
                return;
            }

            logsInFlight = true;
            try {
                let r = await api("/api/logs"); if(!r.ok) return; let x = await r.json();
                document.getElementById('logFilePath').textContent = "Путь: " + x.path;
                document.getElementById('loggingActiveToggle').checked = x.active;

                const threshold = 30;
                const scrollBottom = consoleElem.scrollHeight - consoleElem.clientHeight;
                const isScrolledToBottom = (scrollBottom - consoleElem.scrollTop) <= threshold;

                const linesHtml = x.lines.map(line => {
                    let escLine = esc(line);
                    const match = escLine.match(/^\[([^\]]+)\]\s+\[([^\]]+)\]\s+(.*)$/);
                    if (match) {
                        const [, timestamp, level, message] = match;
                        const lvlClass = level.trim() === 'ERROR' ? 'log-err' : 'log-info';
                        return `<div class="log-line"><span class="log-time">[${timestamp}]</span><span class="log-lvl ${lvlClass}">${level}</span><span>${message}</span></div>`;
                    }
                    return `<div class="log-line"><span>${escLine}</span></div>`;
                }).join('');

                consoleElem.innerHTML = linesHtml;

                if (isScrolledToBottom || !logsLoadedOnce) {
                    consoleElem.scrollTop = consoleElem.scrollHeight;
                    logsLoadedOnce = true;
                }
            } catch (e) {
            } finally {
                logsInFlight = false;
            }
        }

        async function toggleLoggingActive() {
            const active = document.getElementById('loggingActiveToggle').checked;
            let r = await api("/api/logs/toggle", {
                method: "POST",
                body: JSON.stringify({ active })
            });
            if (r.ok) {
                showToast(active ? "Логирование включено" : "Логирование выключено");
            }
        }

        async function clearLogs() {
            if (!confirm("Вы действительно хотите очистить всю историю логов?")) return;
            let r = await api("/api/logs/clear", {method: "POST"});
            if (r.ok) {
                document.getElementById('logConsole').textContent = '';
                showToast("История логов очищена");
            }
        }

