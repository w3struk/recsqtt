        let statsInFlight = false;
        async function loadStats() {
            if (statsInFlight) return;
            statsInFlight = true;
            try {
                let r = await api("/api/stats"); if(!r.ok) return; let x = await r.json();
                if (x.peer_port) window.serverDefaultPeerPort = Number(x.peer_port);
                if (x.public_ip) window.serverPublicIp = x.public_ip;
                document.getElementById('status_val').textContent = x.status === "Active" ? "Активно" : x.status;
                document.getElementById('status_uptime').textContent = "Аптайм: " + uptime(x.uptime);
                document.getElementById('active_val').textContent = x.active;
                const cpuTotal = Number(x.cpu_total) || 0;
                const cpuCapacity = Math.max(100, Number(x.cpu_capacity) || 100);
                const cpuNormalized = Math.min(100, Math.max(0, Number(x.cpu_normalized) || 0));
                document.getElementById('cpu_val').textContent = cpuTotal + '% / ' + cpuCapacity + '%';
                document.getElementById('cpu_bar').style.width = cpuNormalized + '%';
                const ramValue = document.getElementById('ram_val');
                const ramDetail = document.getElementById('ram_detail');
                ramValue.textContent = x.ram_used || String(x.ram || '').split(' / ')[0] || '0 MB';
                ramDetail.textContent = `из ${x.ram_total || '0 MB'} · пик ${x.ram_peak || ramValue.textContent}`;
                ramValue.title = [
                    `RSS: ${x.ram_used || '0 MB'}`,
                    `Анонимная: ${x.ram_anonymous || '0 MB'}`,
                    `Файлы: ${x.ram_file || '0 MB'}`,
                    `Shared: ${x.ram_shared || '0 MB'}`,
                    `Swap: ${x.ram_swap || '0 MB'}`,
                    `Пик: ${x.ram_peak || '0 MB'}`,
                    `PSS: ${x.ram_pss || 'н/д'} · private: ${x.ram_private || 'н/д'} · shared: ${x.ram_shared_rollup || 'н/д'}`,
                    `smaps PSS anon/file/shmem: ${x.ram_anon_rollup || 'н/д'} / ${x.ram_file_rollup || 'н/д'} / ${x.ram_shmem_rollup || 'н/д'} · Anonymous RSS-like: ${x.ram_anonymous_rollup || 'н/д'}`,
                    `Аллокатор: ${x.allocator || 'system'}`,
                    `Сессии: ${Number(x.hot_sessions) || 0}/${Number(x.hot_session_capacity) || 0}`,
                    `TPROXY TCP/UDP: ${Number(x.local_proxy_tcp_sessions) || 0}/${Number(x.local_proxy_udp_flows) || 0}`,
                    `Эпохи движка: ${Number(x.mem_engine_epochs) || 0}`,
                    `Ключи: ${Number(x.mem_derived_keys) || 0} · web-сессии: ${Number(x.mem_web_sessions) || 0}/${Number(x.mem_web_session_capacity) || 0} · login-limit: ${Number(x.mem_login_limits) || 0}`,
                    `stream repair/inventory: ${Number(x.mem_stream_repairs) || 0}/${Number(x.mem_stream_inventory) || 0} · DPI: ${Number(x.mem_dpi_ring) || 0} · логи: ${Number(x.mem_log_ring) || 0} (${Number(x.mem_log_bytes) || 0} B)`,
                    `Циклов memory trim: ${Number(x.memory_trim_count) || 0}${Number(x.memory_trim_last_unix) ? ` · последнее: ${new Date(Number(x.memory_trim_last_unix) * 1000).toLocaleTimeString()}` : ''}`
                ].join('\n');
                document.getElementById('traffic_up').textContent = "↑ " + size(x.up);
                document.getElementById('traffic_down').textContent = "↓ " + size(x.down);
                document.getElementById('traffic_ingress').textContent = `RX ${compactCounter(x.udp_rx_packets)} · TX ${compactCounter(x.udp_tx_packets)}`;

                updateLocalProxyRuntime(
                    Boolean(x.local_proxy_enabled),
                    Boolean(x.local_proxy_active),
                    x.local_proxy_port_listening,
                    Number(x.local_proxy_tcp_sessions) || 0,
                    Number(x.local_proxy_udp_flows) || 0,
                    x.local_proxy_health_error || null
                );
            } catch (e) {
            } finally {
                statsInFlight = false;
            }
        }

