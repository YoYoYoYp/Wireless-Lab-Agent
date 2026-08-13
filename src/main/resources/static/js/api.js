/**
 * API 调用封装
 */
const API_BASE = '';

function getToken() {
    return localStorage.getItem('token') || '';
}

function getUsername() {
    return localStorage.getItem('username') || '';
}

function authHeaders() {
    return {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getToken()
    };
}

async function apiPost(url, body) {
    const res = await fetch(API_BASE + url, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify(body)
    });
    return res.json();
}

async function uploadKnowledge(file) {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('deviceType', 'USRP');
    formData.append('documentType', '实验文档');
    const res = await fetch(API_BASE + 'knowledge/upload', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + getToken() },
        body: formData
    });
    return res.json();
}

async function getKnowledgeResult(taskId) {
    return apiGet('knowledge/result/' + taskId);
}

function getHardwareStatus() {
    return apiGet('hardware/status');
}

function stopHardwareTask() {
    return apiPost('hardware/stop', {});
}

async function apiGet(url) {
    const res = await fetch(API_BASE + url, {
        headers: authHeaders()
    });
    return res.json();
}

function ssePost(url, body, options = {}) {
    return fetch(API_BASE + url, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify(body),
        signal: options.signal
    }).then(res => {
        if (!res.ok) {
            return res.json().then(err => {
                throw new Error(err.message || '请求失败');
            });
        }
        return res.body.getReader();
    });
}

function stopChatStream(streamId) {
    return apiPost('chat/stop', { streamId });
}
