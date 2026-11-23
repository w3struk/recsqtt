        let currentHubFilterQuery = '';
        const hubSelectedPasswords = new Set();

        function handleHubSearch(val) {
            currentHubFilterQuery = (val || '').trim();
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = currentHubFilterQuery ? 'inline-flex' : 'none';
            renderHubClientsList();
        }

        function clearHubSearch() {
            const input = document.getElementById('hubSearchInput');
            if (input) input.value = '';
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = 'none';
            currentHubFilterQuery = '';
            renderHubClientsList();
        }

        function openLinksAndTokensDlg() {
            hubSelectedPasswords.clear();
            currentHubFilterQuery = '';
            const searchInput = document.getElementById('hubSearchInput');
            if (searchInput) searchInput.value = '';
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = 'none';

            const tokenInput = document.getElementById('hubVkTokenInput');
            if (tokenInput) tokenInput.value = sharedVkToken || '';
            const userIdInput = document.getElementById('hubVkUserIdInput');
            if (userIdInput) userIdInput.value = sharedVkUserId || '';
            const hashesInput = document.getElementById('hubVkHashesInput');
            if (hashesInput) hashesInput.value = sharedVkHashes || '';
            const workersInput = document.getElementById('hubWorkersInput');
            if (workersInput) workersInput.value = '0';
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) selectAll.checked = false;
            renderHubClientsList();
            updateHubSelectedCount();
            document.getElementById('linksAndTokensDlg').showModal();
        }

        async function autoSaveHubVkToken() {
            const input = document.getElementById('hubVkTokenInput');
            const token = input ? input.value.trim() : '';
            if (token && (/\s/.test(token) || token.length > 4096)) return false;
            if (token === sharedVkToken) return true;
            const r = await api('/api/settings', {
                method: 'POST',
                body: JSON.stringify({ vk_token: token })
            });
            if (r.ok) {
                sharedVkToken = token;
                renderHubClientsList();
                return true;
            }
            return false;
        }

        async function autoSaveHubVkUserId() {
            const input = document.getElementById('hubVkUserIdInput');
            const userId = input ? input.value.trim() : '';
            if (userId && !/^[0-9]{1,20}$/.test(userId)) {
                showToast('VK user id: только цифры, до 20 символов', 'error');
                if (input) input.value = sharedVkUserId || '';
                return false;
            }
            if (userId === sharedVkUserId) return true;
            const r = await api('/api/settings', {
                method: 'POST',
                body: JSON.stringify({ vk_token_user_id: userId })
            });
            if (r.ok) {
                sharedVkUserId = userId;
                return true;
            }
            return false;
        }

        async function autoSaveHubVkHashes() {
            const input = document.getElementById('hubVkHashesInput');
            const raw = input ? input.value.trim() : '';
            if (raw === sharedVkHashes) return true;
            const r = await api('/api/settings', {
                method: 'POST',
                body: JSON.stringify({ vk_hashes: raw })
            });
            if (r.ok) {
                sharedVkHashes = raw;
                renderHubClientsList();
                return true;
            }
            return false;
        }

        async function applyHubVkTokenDirect() {
            const input = document.getElementById('hubVkTokenInput');
            const token = input ? input.value.trim() : '';
            if (!token) {
                showToast("Введите VK токен", "error");
                return;
            }
            if (/\s/.test(token) || token.length > 4096) {
                showToast("VK токен не должен содержать пробелы и должен быть короче 4097 символов", "error");
                return;
            }
            await autoSaveHubVkToken();
            promptBatchAction('token');
        }

        async function applyHubVkHashesDirect() {
            const input = document.getElementById('hubVkHashesInput');
            const raw = input ? input.value.trim() : '';
            const hashes = raw.split(',').map(s => s.trim()).filter(Boolean);
            if (!hashes.length || hashes.length > 6 || hashes.some(h => h.length < 16 || /\s/.test(h))) {
                showToast("Укажите от 1 до 6 корректных VK-хешей через запятую (каждый от 16 символов без пробелов)", "error");
                return;
            }
            await autoSaveHubVkHashes();
            promptBatchAction('hashes');
        }

        async function applyHubWorkersDirect() {
            const input = document.getElementById('hubWorkersInput');
            const w = parseInt(input ? input.value : '0', 10);
            if (isNaN(w) || w < 0 || (w > 0 && w < 3) || w > 100) {
                showToast("Число потоков должно быть 0 (клиентские) или от 3 до 100", "error");
                return;
            }
            promptBatchAction('workers');
        }

        function renderHubClientsList(preservedChecked = null) {
            const container = document.getElementById('hubClientsList');
            if (!container) return;
            if (preservedChecked !== null) {
                hubSelectedPasswords.clear();
                preservedChecked.forEach(p => hubSelectedPasswords.add(p));
            }
            const allClients = window.clientsData || [];
            if (!allClients.length) {
                container.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 24px;">Нет созданных клиентов</div>';
                updateHubSelectedCount();
                return;
            }

            const groupMap = new Map();
            (window.groupsData || []).forEach(g => {
                if (g && g.id) groupMap.set(g.id, g.name || '');
            });

            let displayClients = [];
            const q = currentHubFilterQuery;

            if (!q) {
                displayClients = allClients.map(c => ({
                    client: c,
                    groupName: c.group_id ? (groupMap.get(c.group_id) || '') : '',
                    score: 0
                }));
            } else {
                const bracketMatch = q.match(/^(.*?)\((.*?)\)(.*)$/);
                let mode = 'name_only';
                let namePart = '';
                let groupPart = '';

                if (bracketMatch) {
                    const outside = (bracketMatch[1] + ' ' + bracketMatch[3]).trim();
                    const inside = bracketMatch[2].trim();
                    if (!outside) {
                        mode = 'group_only';
                        groupPart = inside;
                    } else {
                        mode = 'name_and_group';
                        namePart = outside;
                        groupPart = inside;
                    }
                } else {
                    const openIdx = q.lastIndexOf('(');
                    if (openIdx !== -1) {
                        const outside = q.slice(0, openIdx).trim();
                        const inside = q.slice(openIdx + 1).trim();
                        if (!outside) {
                            mode = 'group_only';
                            groupPart = inside;
                        } else {
                            mode = 'name_and_group';
                            namePart = outside;
                            groupPart = inside;
                        }
                    } else {
                        mode = 'name_only';
                        namePart = q;
                    }
                }

                const scored = [];
                for (const c of allClients) {
                    const gName = c.group_id ? (groupMap.get(c.group_id) || '') : '';
                    const cName = c.name || 'Клиент';
                    let score = 0;

                    if (mode === 'name_only') {
                        score = calcMatchScore(cName, namePart);
                    } else if (mode === 'group_only') {
                        if (c.group_id && gName) {
                            if (!groupPart) {
                                score = 100;
                            } else {
                                score = calcMatchScore(gName, groupPart);
                            }
                        }
                    } else if (mode === 'name_and_group') {
                        const nScore = namePart ? calcMatchScore(cName, namePart) : 0;
                        const gScore = (groupPart && gName) ? calcMatchScore(gName, groupPart) : 0;
                        if (nScore > 0 && gScore > 0) {
                            score = 2000 + nScore + gScore;
                        } else if (nScore > 0) {
                            score = nScore;
                        } else if (gScore > 0) {
                            score = gScore;
                        }
                    }

                    if (score > 0) {
                        scored.push({ client: c, groupName: gName, score });
                    }
                }

                scored.sort((a, b) => b.score - a.score);
                displayClients = scored;
            }

            if (!displayClients.length) {
                container.innerHTML = `<div style="color: var(--text-muted); text-align: center; padding: 24px;">По запросу «<b>${esc(q)}</b>» ничего не найдено</div>`;
                updateHubSelectedCount();
                return;
            }

            container.innerHTML = displayClients.map(item => {
                const c = item.client;
                const groupName = item.groupName;
                const link = c.link || '';
                let badge = '';
                const hashes = String(c.vk_hashes || c.vk_hash || '').trim();
                if (c.vk_token) {
                    badge = '<span class="badge active" style="font-size: 11px;">VK Токен</span>';
                } else if (hashes) {
                    badge = '<span class="badge active" style="font-size: 11px;">VK Хеши</span>';
                } else {
                    badge = '<span class="badge inactive" style="font-size: 11px;">Прямой</span>';
                }
                if (c.workers && c.workers >= 3) {
                    badge += ` <span class="badge active" style="font-size: 11px;">Потоки: ${c.workers}</span>`;
                }
                const isChecked = hubSelectedPasswords.has(c.password);
                const displayName = groupName ? `${c.name || 'Клиент'} (${groupName})` : (c.name || 'Клиент');

                return `
                <div style="background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 8px;">
                    <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap;">
                        <div style="display: flex; align-items: center; gap: 10px;">
                            <input type="checkbox" class="hub-client-cb" value="${esc(c.password)}" ${isChecked ? 'checked' : ''} onchange="handleHubClientCbChange(this)" style="width: 16px; height: 16px; cursor: pointer; accent-color: var(--primary);">
                            <span style="font-weight: 600; font-size: 14px; color: var(--text-main);">${esc(displayName)}</span>
                            ${badge}
                        </div>
                    </div>
                    <div style="display: flex; gap: 8px; align-items: center;">
                        <input readonly value="${esc(link)}" style="flex: 1; height: 40px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; padding: 0 10px; background: var(--surface-hover); color: var(--text-muted); border-radius: 8px;" onclick="this.select()">
                        <button class="btn btn-ghost" style="height: 40px; width: 44px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid var(--border); border-radius: 8px;" onclick="copyHubClientLink('${esc(c.password)}', this)" title="Копировать ссылку">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary);"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                        </button>
                        <button class="btn btn-ghost" style="height: 40px; width: 44px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid var(--border); border-radius: 8px;" onclick="openQrModalForClient('${esc(c.password)}')" title="QR-код">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary);"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect><path d="M7 7h.01M17 7h.01M7 17h.01M17 17h.01"></path></svg>
                        </button>
                    </div>
                </div>
                `;
            }).join('');

            updateHubSelectedCount();
        }

        function handleHubClientCbChange(cb) {
            if (cb.checked) {
                hubSelectedPasswords.add(cb.value);
            } else {
                hubSelectedPasswords.delete(cb.value);
            }
            updateHubSelectedCount();
        }

        function toggleSelectAllHubClients(checked) {
            const cbs = document.querySelectorAll('.hub-client-cb');
            cbs.forEach(cb => {
                cb.checked = checked;
                if (checked) {
                    hubSelectedPasswords.add(cb.value);
                } else {
                    hubSelectedPasswords.delete(cb.value);
                }
            });
            updateHubSelectedCount();
        }

        function updateHubSelectedCount() {
            const visibleCbs = document.querySelectorAll('.hub-client-cb');
            const checkedCount = hubSelectedPasswords.size;
            const countEl = document.getElementById('hubSelectedCount');
            if (countEl) countEl.textContent = `(${checkedCount} выбрано)`;
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) {
                selectAll.checked = (visibleCbs.length > 0 && Array.from(visibleCbs).every(cb => cb.checked));
            }
        }

        async function copyHubClientLink(password, btn = null) {
            const client = (window.clientsData || []).find(c => c.password === password);
            if (!client) return;
            const link = client.link || '';
            if (!link) { showToast('Ссылка клиента недоступна', true); return; }
            if (await copyToClipboard(link)) {
                showToast("Ссылка скопирована");
                if (btn) {
                    const orig = btn.innerHTML;
                    btn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" style="stroke:var(--success)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>`;
                    setTimeout(() => { btn.innerHTML = orig; }, 1500);
                }
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }

        let currentBatchAction = null;

        function promptBatchAction(action) {
            if (!hubSelectedPasswords.size) {
                const checkedCbs = Array.from(document.querySelectorAll('.hub-client-cb:checked'));
                checkedCbs.forEach(cb => hubSelectedPasswords.add(cb.value));
            }
            if (!hubSelectedPasswords.size) {
                showToast("Выберите хотя бы одного клиента", "error");
                return;
            }
            const count = hubSelectedPasswords.size;
            const titleEl = document.getElementById('batchConfirmTitle');
            const msgEl = document.getElementById('batchConfirmMessage');
            const submitBtn = document.getElementById('batchConfirmSubmitBtn');

            if (action === 'token') {
                const tokenVal = (document.getElementById('hubVkTokenInput')?.value || sharedVkToken || '').trim();
                if (!tokenVal) {
                    showToast("Укажите VK токен", "error");
                    return;
                }
                titleEl.textContent = "Применить VK токен";
                msgEl.textContent = `Включить общий VK токен для выбранных клиентов (${count} шт.)? Индивидуальные VK хеши будут очищены.`;
                submitBtn.textContent = "Применить токен";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-token') {
                titleEl.textContent = "Отключить VK токен";
                msgEl.textContent = `Отключить VK токен для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Отключить токен";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'hashes') {
                const raw = (document.getElementById('hubVkHashesInput')?.value || sharedVkHashes || '').trim();
                const hashes = raw.split(',').map(s => s.trim()).filter(Boolean);
                if (!hashes.length || hashes.length > 6 || hashes.some(h => h.length < 16 || /\s/.test(h))) {
                    showToast("Укажите от 1 до 6 корректных VK-хешей через запятую (каждый от 16 символов без пробелов)", "error");
                    return;
                }
                titleEl.textContent = "Применить VK хеши";
                msgEl.textContent = `Установить указанные VK хеши для выбранных клиентов (${count} шт.)? Флаг VK токена будет отключен.`;
                submitBtn.textContent = "Применить хеши";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-hashes') {
                titleEl.textContent = "Очистить VK хеши";
                msgEl.textContent = `Очистить VK хеши для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Очистить хеши";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'workers') {
                const w = parseInt(document.getElementById('hubWorkersInput')?.value || '0', 10);
                if (isNaN(w) || w < 0 || (w > 0 && w < 3) || w > 100) {
                    showToast("Число потоков должно быть 0 или от 3 до 100", "error");
                    return;
                }
                titleEl.textContent = "Установить число потоков";
                msgEl.textContent = `Установить число потоков (${w === 0 ? 'Клиентские настройки (0)' : w}) для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Установить число потоков";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-workers') {
                titleEl.textContent = "Сбросить число потоков";
                msgEl.textContent = `Сбросить число потоков до клиентских настроек (0) для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Сбросить число потоков";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'clear') {
                titleEl.textContent = "Очистить параметры VK";
                msgEl.textContent = `Очистить VK токен и VK хеши у выбранных клиентов (${count} шт.)? Они перейдут в обычный режим прямого подключения.`;
                submitBtn.textContent = "Очистить";
                submitBtn.className = "btn btn-danger";
            }
            currentBatchAction = action;
            submitBtn.onclick = executeBatchAction;
            document.getElementById('batchConfirmDlg').showModal();
        }

        async function executeBatchAction() {
            const action = currentBatchAction;
            if (!action) return;
            const checkedPasses = Array.from(hubSelectedPasswords);
            const clients = (window.clientsData || []).filter(c => checkedPasses.includes(c.password));

            const submitBtn = document.getElementById('batchConfirmSubmitBtn');
            submitBtn.disabled = true;
            submitBtn.textContent = "Применение...";

            let hashesVal = null;
            let tokenVal = null;
            let workersVal = null;

            if (action === 'token') {
                tokenVal = true;
                hashesVal = '';
            } else if (action === 'clear-token') {
                tokenVal = false;
            } else if (action === 'hashes') {
                const raw = (document.getElementById('hubVkHashesInput')?.value || sharedVkHashes || '').trim();
                hashesVal = raw.split(',').map(s => s.trim()).filter(Boolean).join(',');
                tokenVal = false;
            } else if (action === 'clear-hashes') {
                hashesVal = '';
            } else if (action === 'workers') {
                workersVal = parseInt(document.getElementById('hubWorkersInput')?.value || '0', 10) || 0;
            } else if (action === 'clear-workers') {
                workersVal = 0;
                const winp = document.getElementById('hubWorkersInput');
                if (winp) winp.value = '0';
            } else if (action === 'clear') {
                tokenVal = false;
                hashesVal = '';
            }

            try {
                let successCount = 0;
                for (const c of clients) {
                    let remainingDays = 0;
                    if (c.expires) {
                        remainingDays = Math.max(1, Math.ceil((c.expires * 1000 - Date.now()) / 86400000));
                    }
                    const payload = {
                        name: c.name || '',
                        group_id: c.group_id || '',
                        days: c.expires ? remainingDays : 0,
                        hash: (hashesVal !== null) ? hashesVal : (c.vk_hashes || c.vk_hash || ''),
                        vk_token: (tokenVal !== null) ? tokenVal : Boolean(c.vk_token),
                        peer_port: c.peer_port || window.serverDefaultPeerPort || 46000,
                        local_port: c.local_port || 0,
                        workers: (workersVal !== null) ? workersVal : (c.workers || 0)
                    };
                    const r = await api("/api/clients/" + encodeURIComponent(c.password), {
                        method: "POST",
                        body: JSON.stringify(payload)
                    });
                    if (r.ok) {
                        successCount++;
                    }
                }
                document.getElementById('batchConfirmDlg').close();
                showToast(`Обновлено клиентов: ${successCount} из ${clients.length}`);
                await loadClients();
                renderHubClientsList();
                updateHubSelectedCount();
            } finally {
                submitBtn.disabled = false;
                submitBtn.className = "btn btn-primary";
                submitBtn.textContent = "Подтвердить";
                currentBatchAction = null;
            }
        }
