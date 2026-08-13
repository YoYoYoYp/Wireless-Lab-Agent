/**
 * 侧边栏：对话历史管理
 */
let currentChatId = null;

document.addEventListener('DOMContentLoaded', () => {
    if (!localStorage.getItem('token')) {
        window.location.href = 'login.html';
        return;
    }

    document.getElementById('currentUser').textContent = getUsername();
    document.getElementById('logoutBtn').addEventListener('click', logout);
    document.getElementById('newChatBtn').addEventListener('click', () => startNewChat());
    loadConversations();
});

function logout() {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    window.location.href = 'login.html';
}

async function startNewChat() {
    if (typeof stopCurrentStream === 'function') {
        await stopCurrentStream({ silent: true });
    }

    currentChatId = 'conv-' + Date.now();
    document.getElementById('chatMessages').innerHTML = `
        <div class="welcome-screen">
            <div class="welcome-badge">New Chat</div>
            <h2>开始新的无线实验会话</h2>
            <p>可以查询设备文档、检查状态，或发起 USRP 物理层实验。</p>
        </div>
    `;
    document.querySelectorAll('.conv-item').forEach(el => el.classList.remove('active'));
}

async function loadConversations() {
    try {
        const res = await apiGet('conversations');
        if (res.code !== 0) return;

        const list = document.getElementById('conversationList');
        list.innerHTML = '';

        (res.data || []).forEach(conv => {
            const div = document.createElement('div');
            div.className = 'conv-item';
            div.textContent = conv.title;
            div.dataset.chatId = conv.chatId;
            if (conv.chatId === currentChatId) {
                div.classList.add('active');
            }
            div.addEventListener('click', () => switchConversation(conv.chatId, div));
            list.appendChild(div);
        });
    } catch (e) {
        console.error('加载会话列表失败', e);
    }
}

async function switchConversation(chatId, el) {
    if (typeof stopCurrentStream === 'function') {
        await stopCurrentStream({ silent: true });
    }

    currentChatId = chatId;
    document.querySelectorAll('.conv-item').forEach(item => item.classList.remove('active'));
    el.classList.add('active');

    const container = document.getElementById('chatMessages');
    container.innerHTML = '<div class="panel-loading">正在加载历史消息...</div>';

    try {
        const res = await apiGet('conversations/' + chatId);
        container.innerHTML = '';

        if (!res.data || res.data.length === 0) {
            container.innerHTML = '<div class="welcome-screen"><p>当前会话还没有消息记录</p></div>';
            return;
        }

        res.data.forEach(msg => {
            appendMessage(msg.role, msg.content);
        });
    } catch (e) {
        container.innerHTML = '<div class="panel-loading error">历史消息加载失败</div>';
    }

    scrollToBottom();
}
