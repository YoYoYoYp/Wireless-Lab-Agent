/**
 * 登录 / 注册逻辑
 */
document.addEventListener('DOMContentLoaded', () => {
    // 已登录跳转
    if (localStorage.getItem('token')) {
        window.location.href = 'index.html';
        return;
    }

    const tabs = document.querySelectorAll('.tab');
    const loginForm = document.getElementById('loginForm');
    const registerForm = document.getElementById('registerForm');
    const loginError = document.getElementById('loginError');
    const regError = document.getElementById('regError');

    // Tab 切换
    tabs.forEach(t => {
        t.addEventListener('click', () => {
            tabs.forEach(x => x.classList.remove('active'));
            t.classList.add('active');
            const tab = t.dataset.tab;
            loginForm.classList.toggle('hidden', tab !== 'login');
            registerForm.classList.toggle('hidden', tab !== 'register');
            loginError.textContent = '';
            regError.textContent = '';
        });
    });

    // 登录
    loginForm.addEventListener('submit', async e => {
        e.preventDefault();
        loginError.textContent = '';
        const username = document.getElementById('loginUsername').value.trim();
        const password = document.getElementById('loginPassword').value;
        const res = await apiPost('auth/login', { username, password });
        if (res.code === 0) {
            localStorage.setItem('token', res.data.token);
            localStorage.setItem('username', res.data.username);
            window.location.href = 'index.html';
        } else {
            loginError.textContent = res.message;
        }
    });

    // 注册
    registerForm.addEventListener('submit', async e => {
        e.preventDefault();
        regError.textContent = '';
        const username = document.getElementById('regUsername').value.trim();
        const password = document.getElementById('regPassword').value;
        const res = await apiPost('auth/register', { username, password });
        if (res.code === 0) {
            regError.style.color = '#10b981';
            regError.textContent = '注册成功，请切换到登录';
            registerForm.reset();
        } else {
            regError.textContent = res.message;
        }
    });
});
