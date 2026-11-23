        function openEditClientDlgByIndex(idx) {
            if (window.clientsData && window.clientsData[idx]) {
                openEditClientDlg(window.clientsData[idx]);
            }
        }

        function openEditClientDlgByPassword(password) {
            const c = (window.clientsData || []).find(x => x.password === password);
            if (c) openEditClientDlg(c);
        }

        function openNewGroupDlg() {
            document.getElementById('groupNameInput').value = 'Группа' + Math.floor(100 + Math.random() * 900);
            document.getElementById('newGroupDlg').showModal();
        }

        async function createGroup() {
            const name = document.getElementById('groupNameInput').value.trim();
            if (!name) { showToast("Укажите имя группы", "error"); return; }
            const btn = document.getElementById('createGroupBtn');
            btn.disabled = true;
            try {
                let r = await api("/api/groups", {
                    method: "POST",
                    body: JSON.stringify({ name: name })
                });
                if (r.ok) {
                    showToast("Группа создана");
                    document.getElementById('newGroupDlg').close();
                    loadClients();
                } else {
                    showToast(await r.text(), "error");
                }
            } finally {
                btn.disabled = false;
            }
        }

        let isGroupInlineEditing = false;
        function exitGroupInlineEdit() {
            isGroupInlineEditing = false;
            const titleEl = document.getElementById('groupDlgTitle');
            const inputEl = document.getElementById('groupDlgTitleInput');
            const btnEl = document.getElementById('groupDlgEditBtn');
            if (titleEl) titleEl.style.display = '';
            if (inputEl) inputEl.style.display = 'none';
            if (btnEl) {
                btnEl.title = "Переименовать";
                btnEl.style.color = "var(--text-muted)";
                btnEl.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>`;
            }
        }

        async function toggleGroupInlineEdit() {
            const titleEl = document.getElementById('groupDlgTitle');
            const inputEl = document.getElementById('groupDlgTitleInput');
            const btnEl = document.getElementById('groupDlgEditBtn');
            if (!titleEl || !inputEl || !btnEl) return;

            if (!isGroupInlineEditing) {
                isGroupInlineEditing = true;
                inputEl.value = titleEl.textContent.trim();
                titleEl.style.display = 'none';
                inputEl.style.display = 'inline-block';
                inputEl.focus();
                inputEl.select();
                btnEl.title = "Сохранить";
                btnEl.style.color = "var(--primary)";
                btnEl.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>`;
            } else {
                const newName = inputEl.value.trim();
                if (!newName) {
                    showToast("Имя группы не может быть пустым", "error");
                    return;
                }
                const groupId = window.currentViewingGroupId;
                if (!groupId) return;
                btnEl.disabled = true;
                try {
                    let r = await api("/api/groups/" + encodeURIComponent(groupId), {
                        method: "PUT",
                        body: JSON.stringify({ name: newName })
                    });
                    if (r.ok) {
                        showToast("Группа переименована");
                        titleEl.textContent = newName;
                        exitGroupInlineEdit();
                        await loadClients();
                        if (window.currentViewingGroupId) refreshCurrentGroupDlgView();
                    } else {
                        showToast(await r.text(), "error");
                    }
                } finally {
                    btnEl.disabled = false;
                }
            }
        }

        function handleGroupSearch(val) {
            const clearBtn = document.getElementById('groupSearchClearBtn');
            if (clearBtn) clearBtn.style.display = val.trim() ? 'block' : 'none';
            refreshCurrentGroupDlgView();
        }

        function clearGroupSearch() {
            const input = document.getElementById('groupSearchInput');
            if (input) {
                input.value = '';
                const clearBtn = document.getElementById('groupSearchClearBtn');
                if (clearBtn) clearBtn.style.display = 'none';
                refreshCurrentGroupDlgView();
                input.focus();
            }
        }

        function refreshCurrentGroupDlgView() {
            if (!window.currentViewingGroupId) return;
            const group = (window.groupsData || []).find(g => g.id === window.currentViewingGroupId);
            if (!group) return;
            if (!isGroupInlineEditing) {
                document.getElementById('groupDlgTitle').textContent = group.name || 'Группа';
            }
            const groupClients = (window.clientsData || []).filter(c => c.group_id === window.currentViewingGroupId);
            let sumUp = 0, sumDown = 0, sumSessions = 0;
            for (const c of groupClients) {
                sumUp += (c.up || 0);
                sumDown += (c.down || 0);
                sumSessions += (c.active_sessions || 0);
            }
            const statsEl = document.getElementById('groupDlgStatsSummary');
            if (statsEl) {
                statsEl.textContent = `Участники: ${groupClients.length} · Трафик: ↑ ${size(sumUp)} / ↓ ${size(sumDown)} · Потоки: ${sumSessions}`;
            }

            const container = document.getElementById('groupClientsCards');
            if (!container) return;

            if (groupClients.length === 0) {
                container.innerHTML = `<div class="empty-state" style="grid-column: 1/-1; padding: 24px;">В группе пока нет клиентов. Нажмите «Создать пароль», чтобы добавить.</div>`;
                return;
            }

            const query = (document.getElementById('groupSearchInput')?.value || '').trim();
            if (!query) {
                container.innerHTML = groupClients.map(c => renderClientCard(c)).join('');
                refreshPresenceBadges();
                return;
            }

            const matched = [];
            for (const c of groupClients) {
                const sc = calcMatchScore(c.name, query);
                if (sc > 0) {
                    matched.push({ score: sc, html: renderClientCard(c) });
                }
            }

            if (matched.length === 0) {
                container.innerHTML = `<div class="empty-state" style="grid-column: 1/-1; padding: 24px;">По запросу «<b>${esc(query)}</b>» в группе ничего не найдено.</div>`;
                return;
            }

            matched.sort((a, b) => b.score - a.score);
            container.innerHTML = matched.map(m => m.html).join('');
            refreshPresenceBadges();
        }

        function openGroupDlg(groupId) {
            window.currentViewingGroupId = groupId;
            exitGroupInlineEdit();
            const gSearch = document.getElementById('groupSearchInput');
            if (gSearch) gSearch.value = '';
            const gClear = document.getElementById('groupSearchClearBtn');
            if (gClear) gClear.style.display = 'none';
            refreshCurrentGroupDlgView();
            const inputEl = document.getElementById('groupDlgTitleInput');
            if (inputEl) {
                inputEl.onkeydown = (e) => {
                    if (e.key === 'Enter') toggleGroupInlineEdit();
                    else if (e.key === 'Escape') exitGroupInlineEdit();
                };
            }
            document.getElementById('groupDlg').showModal();
        }

        function createClientInCurrentGroup() {
            const group = (window.groupsData || []).find(g => g.id === window.currentViewingGroupId);
            openNewClientDlg(window.currentViewingGroupId, group ? group.name : '');
        }

        async function copyClientSubLink(subUuid, btn) {
            if (!subUuid) {
                showToast("У клиента нет ссылки подписки", "error");
                return;
            }
            const url = window.location.origin + '/' + subUuid + '/sub';
            if (await copyToClipboard(url)) {
                if (btn) {
                    btn.style.color = 'var(--primary)';
                    btn.style.borderColor = 'var(--primary)';
                    setTimeout(() => { btn.style.color = ''; btn.style.borderColor = ''; }, 1500);
                }
                showToast("Ссылка подписки скопирована");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }

        async function copyGroupSubLink(subUuid, btn) {
            if (!subUuid) {
                showToast("У группы нет ссылки подписки", "error");
                return;
            }
            const url = window.location.origin + '/' + subUuid + '/sub-group';
            if (await copyToClipboard(url)) {
                if (btn) {
                    btn.style.color = 'var(--primary)';
                    btn.style.borderColor = 'var(--primary)';
                    setTimeout(() => { btn.style.color = ''; btn.style.borderColor = ''; }, 1500);
                }
                showToast("Ссылка подписки группы скопирована");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }

        async function deleteCurrentGroup() {
            if (!window.currentViewingGroupId) return;
            if (!confirm("Удалить группу? Все ключи внутри этой группы также будут безвозвратно удалены.")) return;
            let r = await api("/api/groups/" + encodeURIComponent(window.currentViewingGroupId), {
                method: "DELETE"
            });
            if (r.ok) {
                showToast("Группа и все её ключи удалены");
                document.getElementById('groupDlg').close();
                window.currentViewingGroupId = null;
                loadClients();
            } else {
                showToast(await r.text(), "error");
            }
        }

