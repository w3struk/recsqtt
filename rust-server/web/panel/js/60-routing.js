        async function openRoutingRulesDlg() {
            await loadRoutingRules();
            document.getElementById('routingRulesDlg').showModal();
        }

        async function loadRoutingRules() {
            let r = await api("/api/routing-rules");
            if (!r.ok) { showToast("Не удалось загрузить правила", "error"); return; }
            window.routingRulesData = await r.json() || [];
            renderRoutingRulesList();
        }

        function renderRoutingRulesList() {
            const container = document.getElementById('routingRulesList');
            if (!window.routingRulesData || window.routingRulesData.length === 0) {
                container.innerHTML = `<div class="empty-state" style="padding: 24px;">Нет созданных правил маршрутизации.</div>`;
                return;
            }
            container.innerHTML = window.routingRulesData.map(rule => {
                const isWhite = rule.mode === 'whitelist';
                const modeText = isWhite ? 'Белый список' : 'Чёрный список';
                const modeBadge = `<span class="badge ${isWhite ? 'active' : 'inactive'}" style="font-size: 11px;">${modeText}</span>`;
                const appsCount = (rule.packages || []).length;
                const domainsCount = (rule.domains || []).length;
                const targetGroups = (rule.target_group_ids || []).length;
                const targetClients = (rule.target_client_passwords || []).length;
                const templateParts = [];
                if (rule.preset_apps) templateParts.push('Приложения');
                if (rule.template_domains) templateParts.push('Домены');
                if (rule.template_ips) templateParts.push('Адреса');
                const templateLabel = templateParts.length ? templateParts.join(' + ') : 'Выкл';
                return `
                <div class="rule-card">
                    <div class="rule-card-header">
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <div class="rule-card-title">${esc(rule.name || 'Без имени')}</div>
                            ${modeBadge}
                        </div>
                        <div style="display: flex; gap: 6px;">
                            <button class="btn btn-ghost" style="padding: 4px 10px; font-size: 12px; height: 30px;" onclick="openEditRoutingRuleDlg('${esc(rule.id)}')">Изменить</button>
                            <button class="btn btn-ghost" style="padding: 4px 10px; font-size: 12px; height: 30px; color: var(--danger);" onclick="deleteRoutingRule('${esc(rule.id)}')">Удалить</button>
                        </div>
                    </div>
                    <div style="display: flex; flex-wrap: wrap; gap: 6px; font-size: 12px;">
                        <span class="rule-chip">Приложений: ${appsCount}</span>
                        <span class="rule-chip">Доменов: ${domainsCount}</span>
                        <span class="rule-chip">Адресов: ${(rule.ips || []).length}</span>
                        <span class="rule-chip">Шаблоны: ${templateLabel}</span>
                        <span class="rule-chip">Целей: ${targetGroups} групп, ${targetClients} клиентов</span>
                    </div>
                </div>
                `;
            }).join('');
        }

        function onRuleAppsModeChange() {
            const mode = document.getElementById('ruleMode').value;
            const row = document.getElementById('rulePresetAppsRow');
            if (row) {
                row.style.display = (mode === 'whitelist') ? 'none' : 'flex';
            }
        }

        function onRuleDomainsModeChange() {
            const mode = document.getElementById('ruleDomainsMode').value;
            const row = document.getElementById('ruleTemplateDomainsRow');
            if (row) {
                row.style.display = (mode === 'whitelist') ? 'none' : 'flex';
            }
        }

        function onRuleIpModeChange() {
            const mode = document.getElementById('ruleIpMode').value;
            const row = document.getElementById('ruleTemplateIpsRow');
            if (row) {
                row.style.display = (mode === 'whitelist') ? 'none' : 'flex';
            }
        }

        function selectAllRuleTargets(selectAll) {
            const cbs = document.querySelectorAll('#ruleTargetsContainer input[type="checkbox"]');
            cbs.forEach(cb => cb.checked = selectAll);
        }

        function toggleRuleTargetItem(e, item) {
            if (e.target.tagName === 'INPUT') return;
            const cb = item.querySelector('input[type="checkbox"]');
            if (cb) {
                cb.checked = !cb.checked;
            }
        }

        let isDragSelecting = false;
        let dragSelectState = false;
        let dragStartItem = null;

        function bindTargetDragSelection(container) {
            if (container._dragBound) return;
            container._dragBound = true;

            container.addEventListener('mousedown', (e) => {
                const item = e.target.closest('.rule-target-item');
                if (!item) return;
                const cb = item.querySelector('input[type="checkbox"]');
                if (!cb) return;
                isDragSelecting = true;
                dragStartItem = item;
                dragSelectState = !cb.checked;
            });

            container.addEventListener('mouseover', (e) => {
                if (!isDragSelecting) return;
                const item = e.target.closest('.rule-target-item');
                if (!item || item === dragStartItem) return;
                const cb = item.querySelector('input[type="checkbox"]');
                if (cb && cb.checked !== dragSelectState) {
                    cb.checked = dragSelectState;
                }
            });

            container.addEventListener('touchstart', (e) => {
                const item = e.target.closest('.rule-target-item');
                if (!item) return;
                const cb = item.querySelector('input[type="checkbox"]');
                if (!cb) return;
                isDragSelecting = true;
                dragStartItem = item;
                dragSelectState = !cb.checked;
            }, { passive: true });

            container.addEventListener('touchmove', (e) => {
                if (!isDragSelecting) return;
                const touch = e.touches[0];
                if (!touch) return;
                const targetEl = document.elementFromPoint(touch.clientX, touch.clientY);
                const item = targetEl ? targetEl.closest('.rule-target-item') : null;
                if (!item || item === dragStartItem) return;
                const cb = item.querySelector('input[type="checkbox"]');
                if (cb && cb.checked !== dragSelectState) {
                    cb.checked = dragSelectState;
                }
            }, { passive: true });
        }

        window.addEventListener('mouseup', () => { isDragSelecting = false; dragStartItem = null; });
        window.addEventListener('touchend', () => { isDragSelecting = false; dragStartItem = null; });

        function openEditRoutingRuleDlg(ruleId) {
            const rule = ruleId ? (window.routingRulesData || []).find(r => r.id === ruleId) : null;
            document.getElementById('ruleId').value = rule ? rule.id : '';
            document.getElementById('editRoutingRuleTitle').textContent = rule ? 'Редактирование правила' : 'Создание правила';

            const randomNum = Math.floor(100 + Math.random() * 900);
            const defaultName = rule ? (rule.name || '') : `Правило${randomNum}`;
            document.getElementById('ruleName').value = defaultName;
            document.getElementById('ruleMode').value = rule ? (rule.mode || 'blacklist') : 'blacklist';
            onRuleAppsModeChange();
            document.getElementById('rulePackages').value = rule && rule.packages ? rule.packages.join('\n') : '';
            document.getElementById('ruleDomainsMode').value = rule ? (rule.domains_mode || (rule.domains_is_whitelist ? 'whitelist' : 'blacklist')) : 'blacklist';
            onRuleDomainsModeChange();
            document.getElementById('ruleDomains').value = rule && (rule.domains || rule.direct_domains) ? (rule.domains || rule.direct_domains).join('\n') : '';
            document.getElementById('ruleTemplateDomains').checked = rule ? Boolean(rule.template_domains) : false;
            document.getElementById('rulePresetApps').checked = rule ? Boolean(rule.preset_apps) : true;
            document.getElementById('ruleIpMode').value = rule ? (rule.ip_mode || (rule.ip_is_whitelist ? 'whitelist' : 'blacklist')) : 'blacklist';
            onRuleIpModeChange();
            document.getElementById('ruleTemplateIps').checked = rule ? Boolean(rule.template_ips) : false;
            document.getElementById('ruleIps').value = rule && rule.ips ? rule.ips.join('\n') : '';
            document.getElementById('ruleDeleteBtn').style.display = rule ? 'inline-block' : 'none';

            const targetsCont = document.getElementById('ruleTargetsContainer');
            let targetsHtml = '';

            const groups = window.groupsData || [];
            if (groups.length > 0) {
                targetsHtml += '<div class="rule-target-section-title">Группы:</div>';
                targetsHtml += groups.map(g => {
                    const checked = rule && rule.target_group_ids && rule.target_group_ids.includes(g.id);
                    return `
                    <div class="rule-target-item" onclick="toggleRuleTargetItem(event, this)">
                        <input type="checkbox" class="rule-target-group-cb" value="${esc(g.id)}" ${checked ? 'checked' : ''}>
                        <div class="rule-target-info">
                            <span class="rule-target-name">${esc(g.name || 'Без названия')}</span>
                            <span class="rule-target-badge">Группа</span>
                        </div>
                    </div>
                    `;
                }).join('');
            }

            const clients = (window.clientsData || []).filter(c => !c.group_id);
            if (clients.length > 0) {
                targetsHtml += '<div class="rule-target-section-title" style="margin-top: 8px;">Отдельные клиенты:</div>';
                targetsHtml += clients.map(c => {
                    const checked = rule && rule.target_client_passwords && rule.target_client_passwords.includes(c.password);
                    return `
                    <div class="rule-target-item" onclick="toggleRuleTargetItem(event, this)">
                        <input type="checkbox" class="rule-target-client-cb" value="${esc(c.password)}" ${checked ? 'checked' : ''}>
                        <div class="rule-target-info">
                            <span class="rule-target-name">${esc(c.name || 'Клиент')}</span>
                            <span class="rule-target-meta">${esc(c.password)}</span>
                        </div>
                    </div>
                    `;
                }).join('');
            }

            targetsCont.innerHTML = targetsHtml || '<div style="color: var(--text-muted); font-size: 12px; padding: 12px; text-align: center;">Нет доступных групп или клиентов</div>';
            bindTargetDragSelection(targetsCont);
            document.getElementById('editRoutingRuleDlg').showModal();
        }

        async function saveRoutingRule() {
            const id = document.getElementById('ruleId').value;
            let name = document.getElementById('ruleName').value.trim();
            if (!name) name = "Правило";
            const mode = document.getElementById('ruleMode').value;

            const packages = document.getElementById('rulePackages').value
                .split(/[\n,]/)
                .map(s => s.trim())
                .filter(Boolean);

            const rawDomains = document.getElementById('ruleDomains').value
                .split(/[\n,]/);
            const domains = [];
            const seen = new Set();
            let hadGarbage = false;
            for (let raw of rawDomains) {
                let s = raw.trim();
                if (!s) continue;
                s = s.replace(/^[a-zA-Z]+:\/\//, '');
                s = s.split(/[/?#]/)[0];
                s = s.replace(/:\d+$/, '');
                s = s.replace(/^["'\[\(]+|["'\]\)]+$/g, '');
                s = s.replace(/\.+$/, '').trim();
                if (!s) { hadGarbage = true; continue; }
                const isWildcardDomain = /^(\*\.)?([a-zA-Z0-9\u0400-\u04FF]([a-zA-Z0-9\u0400-\u04FF\-]{0,61}[a-zA-Z0-9\u0400-\u04FF])?\.)+[a-zA-Z\u0400-\u04FF]{2,}$/i.test(s);
                const isIpv4 = /^(\d{1,3}\.){3}\d{1,3}$/.test(s);
                const isIpv6 = /^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$/.test(s);
                const isSimple = /^[a-zA-Z0-9\u0400-\u04FF\-_\.]+$/.test(s) && (s.includes('.') || s === 'localhost');
                if (isWildcardDomain || isIpv4 || isIpv6 || isSimple) {
                    const norm = s.toLowerCase();
                    if (!seen.has(norm)) {
                        seen.add(norm);
                        domains.push(norm);
                    }
                } else {
                    hadGarbage = true;
                }
            }
            document.getElementById('ruleDomains').value = domains.join('\n');
            if (hadGarbage) {
                const domainsEl = document.getElementById('ruleDomains');
                if (domainsEl) {
                    domainsEl.style.borderColor = 'var(--danger)';
                    setTimeout(() => { if (domainsEl) domainsEl.style.borderColor = ''; }, 2000);
                }
            }

            const domains_mode = document.getElementById('ruleDomainsMode').value;
            const template_domains = (domains_mode === 'whitelist') ? false : document.getElementById('ruleTemplateDomains').checked;
            const ip_mode = document.getElementById('ruleIpMode').value;
            const template_ips = (ip_mode === 'whitelist') ? false : document.getElementById('ruleTemplateIps').checked;
            const rawIps = document.getElementById('ruleIps').value.split(/[\n,]/);
            const ips = [];
            const seenIps = new Set();
            for (let raw of rawIps) {
                let s = raw.trim();
                if (!s) continue;
                const isCidr = /^(\d{1,3}\.){3}\d{1,3}\/(3[0-2]|[12]?\d)$/.test(s);
                const isSingle = /^(\d{1,3}\.){3}\d{1,3}$/.test(s);
                const isRange = /^(\d{1,3}\.){3}\d{1,3}\s*-\s*(\d{1,3}\.){3}\d{1,3}$/.test(s);
                if (isCidr || isSingle || isRange) {
                    const norm = s.replace(/\s+/g, '');
                    if (!seenIps.has(norm)) {
                        seenIps.add(norm);
                        ips.push(norm);
                    }
                }
            }
            document.getElementById('ruleIps').value = ips.join('\n');

            const target_group_ids = Array.from(document.querySelectorAll('.rule-target-group-cb:checked')).map(cb => cb.value);
            const target_client_passwords = Array.from(document.querySelectorAll('.rule-target-client-cb:checked')).map(cb => cb.value);

            const btn = document.getElementById('ruleSaveBtn');
            btn.disabled = true;
            try {
                const payload = {
                    name,
                    mode,
                    is_whitelist: mode === 'whitelist',
                    packages,
                    domains_mode,
                    domains_is_whitelist: domains_mode === 'whitelist',
                    direct_domains: domains,
                    template_domains,
                    preset_apps: (mode === 'whitelist') ? false : document.getElementById('rulePresetApps').checked,
                    ip_mode,
                    ip_is_whitelist: ip_mode === 'whitelist',
                    template_ips,
                    ips,
                    assigned_groups: target_group_ids,
                    assigned_clients: target_client_passwords
                };
                let r;
                if (id) {
                    r = await api("/api/routing-rules/" + encodeURIComponent(id), {
                        method: "PUT",
                        body: JSON.stringify(payload)
                    });
                } else {
                    r = await api("/api/routing-rules", {
                        method: "POST",
                        body: JSON.stringify(payload)
                    });
                }
                if (r.ok) {
                    showToast("Правило сохранено");
                    document.getElementById('editRoutingRuleDlg').close();
                    await loadRoutingRules();
                    loadClients();
                } else {
                    showToast(await r.text(), "error");
                }
            } finally {
                btn.disabled = false;
            }
        }

