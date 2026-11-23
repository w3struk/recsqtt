        function calcMatchScore(text, query) {
            if (!text || !query) return 0;
            const t = String(text).trim().toLowerCase();
            const q = String(query).trim().toLowerCase();
            if (!t || !q) return 0;
            if (t === q) return 1000;
            if (t.startsWith(q)) return 500 + Math.max(0, 100 - t.length);
            const words = t.split(/[\s,._\-#№]+|(?<=[^\d\s])(?=\d)|(?<=\d)(?=[^\d\s])/);
            for (const w of words) {
                if (w === q) return 450 + Math.max(0, 100 - w.length);
                if (w.startsWith(q)) return 400 + Math.max(0, 100 - w.length);
            }
            const idx = t.indexOf(q);
            if (idx !== -1) return 200 + Math.max(0, 100 - idx);
            return 0;
        }

        function handleMainSearch(val) {
            const clearBtn = document.getElementById('mainSearchClearBtn');
            if (clearBtn) clearBtn.style.display = val.trim() ? 'block' : 'none';
            renderMainClientsCards();
        }

        function clearMainSearch() {
            const input = document.getElementById('mainSearchInput');
            if (input) {
                input.value = '';
                const clearBtn = document.getElementById('mainSearchClearBtn');
                if (clearBtn) clearBtn.style.display = 'none';
                renderMainClientsCards();
                input.focus();
            }
        }

        function renderMainClientsCards() {
            const container = document.getElementById('clientsCards');
            if (!container) return;

            const groupStats = {};
            for (const g of (window.groupsData || [])) {
                groupStats[g.id] = { count: 0, up: 0, down: 0, active_sessions: 0, clients: [] };
            }
            const standaloneClients = [];
            for (const c of (window.clientsData || [])) {
                if (c.group_id && groupStats[c.group_id]) {
                    groupStats[c.group_id].count++;
                    groupStats[c.group_id].up += (c.up || 0);
                    groupStats[c.group_id].down += (c.down || 0);
                    groupStats[c.group_id].active_sessions += (c.active_sessions || 0);
                    groupStats[c.group_id].clients.push(c);
                } else {
                    standaloneClients.push(c);
                }
            }

            const query = (document.getElementById('mainSearchInput')?.value || '').trim();

            if (!query) {
                const groupsHtml = (window.groupsData || []).map(g => renderGroupCard(g, groupStats[g.id])).join('');
                const standaloneHtml = standaloneClients.map(x => renderClientCard(x)).join('');
                container.innerHTML = groupsHtml + standaloneHtml;
                if (!groupsHtml && !standaloneHtml) {
                    container.innerHTML = `<div class="empty-state" style="grid-column: 1/-1;">Клиентов ещё нет. Создайте первого клиента.</div>`;
                }
                refreshPresenceBadges();
                return;
            }

            const results = [];

            for (const g of (window.groupsData || [])) {
                let bestScore = calcMatchScore(g.name, query);
                const st = groupStats[g.id] || { count: 0, clients: [] };
                for (const c of st.clients) {
                    const sc = calcMatchScore(c.name, query);
                    if (sc > bestScore) bestScore = sc;
                }
                if (bestScore > 0) {
                    results.push({
                        type: 'group',
                        score: bestScore,
                        html: renderGroupCard(g, st)
                    });
                }
            }

            for (const c of standaloneClients) {
                const sc = calcMatchScore(c.name, query);
                if (sc > 0) {
                    results.push({
                        type: 'client',
                        score: sc,
                        html: renderClientCard(c)
                    });
                }
            }

            if (results.length === 0) {
                container.innerHTML = `<div class="empty-state" style="grid-column: 1/-1; padding: 32px 16px;">По запросу «<b>${esc(query)}</b>» ничего не найдено.</div>`;
                return;
            }

            results.sort((a, b) => b.score - a.score);
            container.innerHTML = results.map(r => r.html).join('');
            refreshPresenceBadges();
        }

        let clientsInFlight = false;
        async function loadClients() {
            if (clientsInFlight) return;
            clientsInFlight = true;
            try {
                const [rC, rG] = await Promise.all([api("/api/clients"), api("/api/groups")]);
                if (!rC.ok) return;
                const a = await rC.json() || [];
                window.clientsData = a;
                updatePresenceClock(a);
                if (a.length > 0 && a[0].peer_port) {
                    window.serverDefaultPeerPort = a[0].peer_port;
                }
                if (rG && rG.ok) {
                    window.groupsData = await rG.json() || [];
                }

                renderMainClientsCards();

                if (window.currentViewingGroupId) {
                    refreshCurrentGroupDlgView();
                }
                refreshPresenceBadges();
            } catch (e) {
            } finally {
                clientsInFlight = false;
            }
        }

