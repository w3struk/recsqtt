        function renderClientCard(x) {
            const expiryStr = x.expires ? new Date(x.expires * 1000).toLocaleDateString() : "∞ Бессрочно";
            return `
            <div class="client-card" onclick="openEditClientDlgByPassword('${esc(x.password)}')">
                <div class="client-card-header">
                    <div class="client-card-title">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary); flex-shrink: 0;"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
                        <span title="${esc(x.name || 'Клиент')}">${esc(x.name || 'Клиент')}</span>
                    </div>
                    <div class="client-card-badges">
                        <span class="badge ${x.active ? "active" : "inactive"}">${x.active ? "Участник" : "Неактивно"}</span>
                        <span class="badge presence-badge" data-presence="badge" data-last-online="${Number(x.last_online) || 0}" style="display: none;">в сети</span>
                    </div>
                </div>
                <div class="client-card-body">
                    <div class="client-card-row">
                        <span class="client-card-label">IP:</span>
                        <span class="client-card-value">${esc(x.ip || '—')}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Срок действия:</span>
                        <span class="client-card-value">${expiryStr}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Онлайн:</span>
                        <span class="client-card-value">${formatPresenceTime(x.last_online)}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Трафик:</span>
                        <span class="client-card-value" style="font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace;">↑ ${size(x.up)} / ↓ ${size(x.down)}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Потоки:</span>
                        <span class="client-card-value">${x.active_sessions}</span>
                    </div>
                    ${x.sub_uuid ? `
                    <button class="btn btn-ghost client-sub-btn" onclick="event.stopPropagation(); copyClientSubLink('${x.sub_uuid}', this)" title="Скопировать ссылку подписки">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                        <span>Скопировать ссылку подписки</span>
                    </button>
                    ` : ''}
                </div>
            </div>
            `;
        }

        function renderGroupCard(g, st) {
            const stats = st || { count: 0, up: 0, down: 0, active_sessions: 0 };
            return `
            <div class="client-card group-card" onclick="openGroupDlg('${esc(g.id)}')">
                <div class="client-card-header">
                    <div class="client-card-title">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary); flex-shrink: 0;"><circle cx="12" cy="7" r="3.5"></circle><path d="M6 21v-2a4 4 0 0 1 8 0v2"></path><path d="M5.5 10a3 3 0 0 1 0-6"></path><path d="M2 21v-1.5a3.5 3.5 0 0 1 3-3.4"></path><path d="M18.5 4a3 3 0 0 1 0 6"></path><path d="M19 16.1a3.5 3.5 0 0 1 3 3.4V21"></path></svg>
                        <span title="${esc(g.name || 'Без названия')}">${esc(g.name || 'Без названия')}</span>
                    </div>
                    <div class="client-card-badges">
                        <span class="badge active">Группа</span>
                    </div>
                </div>
                <div class="client-card-body">
                    <div class="client-card-row">
                        <span class="client-card-label">Участники:</span>
                        <span class="client-card-value">${stats.count}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Срок действия:</span>
                        <span class="client-card-value">—</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Трафик:</span>
                        <span class="client-card-value" style="font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace;">↑ ${size(stats.up)} / ↓ ${size(stats.down)}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Потоки:</span>
                        <span class="client-card-value">${stats.active_sessions}</span>
                    </div>
                    ${g.sub_uuid ? `
                    <button class="btn btn-ghost" style="width: 100%; margin-top: 8px; display: flex; align-items: center; justify-content: center; gap: 6px; font-size: 12px; padding: 7px 10px; border: 1px solid var(--primary); color: var(--primary); border-radius: 8px; font-weight: 500;" onclick="event.stopPropagation(); copyGroupSubLink('${g.sub_uuid}', this)" title="Скопировать ссылку подписки">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                        <span>Скопировать ссылку подписки</span>
                    </button>
                    ` : ''}
                </div>
            </div>
            `;
        }

