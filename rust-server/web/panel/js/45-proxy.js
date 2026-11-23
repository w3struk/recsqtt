        let localProxyData = { active_profile_id: '', profiles: [], route_active: false, port_listening: true, health_error: null, tcp_sessions: 0, udp_flows: 0 };
        let profileDlgEditId = null;
        let profileDlgSaving = false;

        function renderProxyProfiles() {
            const container = document.getElementById('proxyProfilesList');
            if (!container) return;
            const { profiles, active_profile_id, route_active, port_listening, health_error, tcp_sessions, udp_flows } = localProxyData;
            if (!profiles.length) {
                container.innerHTML = '<div class="proxy-empty">Нет профилей. Нажмите кнопку <strong>+</strong> чтобы добавить.</div>';
                return;
            }
            const playIcon = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7.5 4.9v14.2a1 1 0 0 0 1.53.85l11.3-7.1a1 1 0 0 0 0-1.7L9.03 4.05a1 1 0 0 0-1.53.85z"/></svg>';
            const stopIcon = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="5.5" y="5.5" width="13" height="13" rx="2"/></svg>';
            const editIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>';
            const trashIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path><line x1="10" y1="11" x2="10" y2="17"></line><line x1="14" y1="11" x2="14" y2="17"></line></svg>';
            container.innerHTML = profiles.map(p => {
                const isActive = p.id === active_profile_id;
                let badge = '';
                if (isActive) {
                    if (route_active) {
                        badge = `<span style="font-size:11px;font-weight:600;color:var(--success);margin-left:6px;">Активен</span>`;
                    } else if (port_listening === false) {
                        badge = `<span style="font-size:11px;font-weight:600;color:var(--danger);margin-left:6px;">Порт не слушается</span>`;
                    } else if (health_error) {
                        badge = `<span style="font-size:11px;font-weight:600;color:var(--danger);margin-left:6px;" title="${esc(health_error)}">Проба не прошла (Direct)</span>`;
                    } else {
                        badge = `<span style="font-size:11px;font-weight:600;color:#f59e0b;margin-left:6px;">Подключение...</span>`;
                    }
                }
                let meta = p.username ? `${p.port} · ${p.username}` : `${p.port}`;
                if (isActive) {
                    if (route_active) {
                        meta += ` · ${tcp_sessions} TCP · ${udp_flows} UDP`;
                    } else if (health_error) {
                        meta += ` · Ошибка: ${esc(health_error)}`;
                    }
                }
                const toggleBtn = isActive
                    ? `<button title="Деактивировать" onclick="deactivateProxy()">${stopIcon}</button>`
                    : `<button title="Активировать" onclick="activateProxy('${esc(p.id)}')">${playIcon}</button>`;
                return `<div class="proxy-profile-card${isActive ? ' is-active' : ''}" data-id="${esc(p.id)}">
                    <div class="proxy-profile-dot"></div>
                    <div class="proxy-profile-body">
                        <div class="proxy-profile-name">${esc(p.name)}${badge}</div>
                        <div class="proxy-profile-meta">${meta}</div>
                    </div>
                    <div class="proxy-profile-actions">
                        ${toggleBtn}
                        <button title="Редактировать" onclick="openProfileDlg('${esc(p.id)}')">${editIcon}</button>
                        <button class="danger" title="Удалить" onclick="deleteProxy('${esc(p.id)}','${esc(p.name)}')">${trashIcon}</button>
                    </div>
                </div>`;
            }).join('');
        }

        function updateLocalProxyRuntime(enabled, active, portListening, tcpSessions, udpFlows, healthError) {
            const routeActive = Boolean(enabled) && Boolean(active);
            const listening = portListening !== false;
            const tcp = Number(tcpSessions) || 0;
            const udp = Number(udpFlows) || 0;
            const err = healthError || null;
            if (
                localProxyData.route_active === routeActive &&
                localProxyData.port_listening === listening &&
                localProxyData.health_error === err &&
                localProxyData.tcp_sessions === tcp &&
                localProxyData.udp_flows === udp
            ) return;
            localProxyData.route_active = routeActive;
            localProxyData.port_listening = listening;
            localProxyData.health_error = err;
            localProxyData.tcp_sessions = tcp;
            localProxyData.udp_flows = udp;
            if (document.getElementById('settings-section').style.display !== 'none') {
                renderProxyProfiles();
            }
        }

        async function loadLocalProxy() {
            const r = await api('/api/local-proxy');
            if (!r.ok) return;
            localProxyData = await r.json();
            renderProxyProfiles();
        }

        function openProfileDlg(editId) {
            profileDlgEditId = editId || null;
            const dlg = document.getElementById('profileDlg');
            const title = document.getElementById('profileDlgTitle');
            const saveBtn = document.getElementById('profileDlgSave');
            if (editId) {
                const p = localProxyData.profiles.find(x => x.id === editId);
                if (!p) return;
                title.textContent = 'Редактировать профиль';
                saveBtn.textContent = 'Сохранить';
                document.getElementById('profileDlgName').value = p.name;
                document.getElementById('profileDlgPort').value = p.port;
                document.getElementById('profileDlgUser').value = p.username || '';
                document.getElementById('profileDlgPass').value = p.password || '';
            } else {
                title.textContent = 'Новый профиль';
                saveBtn.textContent = 'Создать';
                document.getElementById('profileDlgName').value = '';
                document.getElementById('profileDlgPort').value = '45000';
                document.getElementById('profileDlgUser').value = '';
                document.getElementById('profileDlgPass').value = '';
            }
            dlg.showModal();
        }

        async function saveProfileDlg() {
            if (profileDlgSaving) return;
            const name = document.getElementById('profileDlgName').value.trim();
            const port = parseInt(document.getElementById('profileDlgPort').value, 10);
            const username = document.getElementById('profileDlgUser').value;
            const password = document.getElementById('profileDlgPass').value;
            if (!port || port < 1 || port > 65535) {
                showToast('Укажите порт от 1 до 65535', 'error'); return;
            }
            if (password && !username) {
                showToast('Для пароля SOCKS5 укажите логин', 'error'); return;
            }
            const body = JSON.stringify({ name, port, username, password });
            const btn = document.getElementById('profileDlgSave');
            profileDlgSaving = true;
            btn.disabled = true;
            btn.textContent = 'Сохранение...';
            try {
                let r;
                if (profileDlgEditId) {
                    r = await api(`/api/local-proxy/profiles/${profileDlgEditId}`, { method: 'PUT', body });
                } else {
                    r = await api('/api/local-proxy', { method: 'POST', body });
                }
                if (!r.ok) {
                    showToast((await r.text()) || 'Ошибка', 'error'); return;
                }
                const data = await r.json().catch(() => ({}));
                document.getElementById('profileDlg').close();
                showToast(profileDlgEditId ? 'Профиль обновлён' : 'Профиль создан', 'success');
                if (data.port_listening === false) {
                    showToast('Указанный вами порт никем не слушается', 'error');
                }
                await loadLocalProxy();
            } finally {
                profileDlgSaving = false;
                btn.disabled = false;
                btn.textContent = profileDlgEditId ? 'Сохранить' : 'Создать';
            }
        }

        async function activateProxy(id) {
            const r = await api(`/api/local-proxy/activate/${id}`, { method: 'POST', body: '{}' });
            if (!r.ok) { showToast((await r.text()) || 'Ошибка активации', 'error'); return; }
            showToast('Профиль активирован', 'success');
            await loadLocalProxy();
        }

        async function deactivateProxy() {
            const r = await api('/api/local-proxy/deactivate', { method: 'POST', body: '{}' });
            if (!r.ok) { showToast((await r.text()) || 'Ошибка', 'error'); return; }
            showToast('Маршрут SOCKS5 отключён', 'success');
            await loadLocalProxy();
        }

        async function deleteProxy(id, name) {
            showConfirm(`Удалить профиль «${name}»?`, `Удаление профиля необратимо.`, 'Удалить', 'btn-danger', async () => {
                const r = await api(`/api/local-proxy/profiles/${id}`, { method: 'DELETE', body: '{}' });
                if (!r.ok) { showToast((await r.text()) || 'Ошибка', 'error'); return; }
                showToast('Профиль удалён', 'success');
                await loadLocalProxy();
            });
        }

