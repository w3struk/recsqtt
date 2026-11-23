        function openNewClientDlg(groupId = null, groupName = null) {
            const targetGid = groupId || '';
            const scopeClients = (window.clientsData || []).filter(c => (c.group_id || '') === targetGid);
            document.getElementById('cname').value = generateUniqueClientName(scopeClients.map(c => c.name));
            document.getElementById('days').value = '30';
            const workersInput = document.getElementById('new_workers');
            if (workersInput) workersInput.value = '0';
            const groupNotice = document.getElementById('newDlgGroupNotice');
            const groupNameSpan = document.getElementById('newDlgGroupName');
            const groupIdInput = document.getElementById('newDlgGroupId');
            if (groupId) {
                groupNotice.style.display = 'block';
                groupNameSpan.textContent = groupName || groupId;
                groupIdInput.value = groupId;
            } else {
                groupNotice.style.display = 'none';
                groupNameSpan.textContent = '';
                groupIdInput.value = '';
            }
            document.getElementById('newDlg').showModal();
        }

        async function createClient() {
            const btn = document.getElementById('createBtn');
            btn.textContent = 'Создание...'; btn.disabled = true;

            try {
                const targetGid = document.getElementById('newDlgGroupId').value || "";
                let name = document.getElementById('cname').value.trim();
                if (!name) {
                    const scopeClients = (window.clientsData || []).filter(c => (c.group_id || '') === targetGid);
                    name = generateUniqueClientName(scopeClients.map(c => c.name));
                }
                const workersVal = parseInt(document.getElementById('new_workers')?.value || '0', 10) || 0;
                let r = await api("/api/clients", {
                    method: "POST",
                    body: JSON.stringify({
                        name: name,
                        group_id: targetGid,
                        days: +document.getElementById('days').value,
                        hash: "",
                        workers: workersVal
                    })
                });

                if(!r.ok) { showToast(await r.text(), "error"); } else {
                    document.getElementById('newDlg').close();
                    showToast("Ключ успешно создан");
                    loadClients();
                }
            } finally { btn.textContent = 'Создать'; btn.disabled = false; }
        }

        let activeClient = null;

        function openEditClientDlg(c) {
            activeClient = c;

            document.getElementById('edit_name').value = c.name || '';
            const groupSelect = document.getElementById('edit_group');
            if (groupSelect) {
                groupSelect.innerHTML = '<option value="">Без группы</option>' + (window.groupsData || []).map(g =>
                    `<option value="${esc(g.id)}">${esc(g.name || 'Без названия')}</option>`
                ).join('');
                groupSelect.value = c.group_id || "";
            }
            document.getElementById('edit_pass').value = c.password || '';

            let remainingDays = 0;
            if (c.expires) {
                remainingDays = Math.max(0, Math.ceil((c.expires * 1000 - Date.now()) / 86400000));
            }
            document.getElementById('edit_days').value = c.expires ? remainingDays : 0;

            const editWorkers = document.getElementById('edit_workers');
            if (editWorkers) {
                editWorkers.value = c.workers || 0;
            }

            document.getElementById('edit_ip_info').textContent = c.ip || '—';
            document.getElementById('edit_traffic_info').textContent = `↑ ${size(c.up)} / ↓ ${size(c.down)}`;

            document.getElementById('edit_statusBtn').innerHTML = c.active ? 'Выкл' : 'Вкл';

            document.getElementById('editDlg').showModal();
        }

        async function copyPasswordText() {
            let val = document.getElementById('edit_pass').value;
            if (await copyToClipboard(val)) {
                showToast("Пароль скопирован");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }

        async function executeToggle() {
            if (!activeClient) return;
            let r = await api("/api/clients/" + encodeURIComponent(activeClient.password) + "/toggle", {method: "POST"});
            if (r.ok) {
                showToast("Статус изменен");
                document.getElementById('editDlg').close();
                loadClients();
            }
        }

        async function executeUnbind() {
            if (!activeClient) return;
            let r = await api("/api/clients/" + encodeURIComponent(activeClient.password) + "/unbind", {method: "POST"});
            if (r.ok) {
                showToast("Привязка сброшена");
                document.getElementById('editDlg').close();
                loadClients();
            }
        }

        async function executeDelete() {
            if (!activeClient) return;
            let r = await api("/api/clients/" + encodeURIComponent(activeClient.password), {method: "DELETE"});
            if (r.ok) {
                showToast("Доступ удален");
                document.getElementById('editDlg').close();
                loadClients();
            }
        }

        async function saveClientChanges() {
            if (!activeClient) return;
            const btn = document.getElementById('edit_saveBtn');
            btn.textContent = 'Сохранение...'; btn.disabled = true;

            try {
                const targetGid = document.getElementById('edit_group') ? document.getElementById('edit_group').value : "";
                let name = document.getElementById('edit_name').value.trim();
                if (!name) {
                    const scopeClients = (window.clientsData || []).filter(c => (c.group_id || '') === targetGid && c.password !== activeClient.password);
                    name = generateUniqueClientName(scopeClients.map(c => c.name));
                }
                let r = await api("/api/clients/" + encodeURIComponent(activeClient.password), {
                    method: "POST",
                    body: JSON.stringify({
                        name: name,
                        group_id: targetGid,
                        days: +document.getElementById('edit_days').value,
                        hash: activeClient.vk_hashes || activeClient.vk_hash || "",
                        vk_token: Boolean(activeClient.vk_token),
                        peer_port: activeClient.peer_port || window.serverDefaultPeerPort || 46000,
                        local_port: activeClient.local_port || 0,
                        workers: parseInt(document.getElementById('edit_workers')?.value || '0', 10) || 0
                    })
                });

                if(!r.ok) { showToast(await r.text(), "error"); } else {
                    showToast("Изменения сохранены");
                    document.getElementById('editDlg').close();
                    loadClients();
                }
            } finally { btn.textContent = 'Сохранить'; btn.disabled = false; }
        }

