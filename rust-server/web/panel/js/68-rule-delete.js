        async function deleteRoutingRule(ruleId) {
            if (!confirm("Удалить правило маршрутизации?")) return;
            let r = await api("/api/routing-rules/" + encodeURIComponent(ruleId), { method: "DELETE" });
            if (r.ok) {
                showToast("Правило удалено");
                await loadRoutingRules();
                loadClients();
            } else {
                showToast(await r.text(), "error");
            }
        }

        async function deleteCurrentRoutingRule() {
            const id = document.getElementById('ruleId').value;
            if (id) {
                document.getElementById('editRoutingRuleDlg').close();
                await deleteRoutingRule(id);
            }
        }

