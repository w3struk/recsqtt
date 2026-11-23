        let presenceClock = null;

        function updatePresenceClock(rows) {
            let serverNow = 0, windowSecs = 0;
            for (const row of (rows || [])) {
                const candidate = Number(row && row.server_now) || 0;
                if (candidate > serverNow) serverNow = candidate;
                const win = Number(row && row.presence_window_secs) || 0;
                if (win > 0) windowSecs = win;
            }
            if (serverNow > 0 && windowSecs > 0) {
                presenceClock = { serverNow, windowSecs, receivedAt: Date.now() };
            }
        }

        function presenceServerNow() {
            if (!presenceClock) return null;
            return presenceClock.serverNow + Math.floor((Date.now() - presenceClock.receivedAt) / 1000);
        }

        function presenceIsOnline(lastOnline, now) {
            if (!presenceClock) return false;
            const t = Number(lastOnline) || 0;
            return t > 0 && now >= t && (now - t) <= presenceClock.windowSecs;
        }

        function formatPresenceTime(lastOnline) {
            const t = Number(lastOnline) || 0;
            if (t <= 0) return 'нет данных';
            return new Date(t * 1000).toLocaleString(undefined, { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
        }

        function refreshPresenceBadges() {
            if (document.hidden) return;
            const now = presenceServerNow();
            if (now === null) return;
            document.querySelectorAll('[data-presence="badge"]').forEach(el => {
                const last = Number(el.getAttribute('data-last-online')) || 0;
                el.style.display = presenceIsOnline(last, now) ? '' : 'none';
            });
        }

        setInterval(refreshPresenceBadges, 20000);
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) refreshPresenceBadges();
        });

        window.clientsData = [];
        window.groupsData = [];
        window.routingRulesData = [];
        window.currentViewingGroupId = null;

        function generateUniqueClientName(existingNames) {
            const set = new Set((existingNames || []).map(n => String(n || '').trim().toLowerCase()));
            for (let i = 0; i < 1000; i++) {
                const candidate = 'Клиент' + Math.floor(100 + Math.random() * 900);
                if (!set.has(candidate.toLowerCase())) {
                    return candidate;
                }
            }
            return 'Клиент' + Math.floor(100 + Math.random() * 900);
        }

