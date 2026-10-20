<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Login - Smart Attendance System</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/style.css">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
</head>
<body>

    <nav class="navbar">
        <a href="${pageContext.request.contextPath}/login" class="nav-brand">
            <div class="brand-icon">⚡</div>
            <span>SmartAttendance</span>
        </a>
        <div class="nav-actions">
            <span class="user-badge">OOP + Java + MySQL</span>
        </div>
    </nav>

    <main class="container" style="display:flex; justify-content:center; align-items:center;">
        <div class="glass-card" style="width: 100%; max-width: 440px;">
            <div style="text-align: center; margin-bottom: 2rem;">
                <h1 class="page-title" style="font-size: 1.8rem;">Welcome Back</h1>
                <p class="page-subtitle">Dynamic QR Attendance Portal</p>
            </div>

            <% if (request.getAttribute("errorMessage") != null) { %>
                <div class="alert alert-danger">
                    <span>⚠️</span>
                    <span><%= request.getAttribute("errorMessage") %></span>
                </div>
            <% } %>

            <% if ("logged_out".equals(request.getParameter("msg"))) { %>
                <div class="alert alert-info">
                    <span>ℹ️</span>
                    <span>You have been successfully logged out.</span>
                </div>
            <% } else if ("login_to_scan".equals(request.getParameter("msg"))) { %>
                <div class="alert alert-info">
                    <span>📱</span>
                    <span>Please log in as a student to record your attendance.</span>
                </div>
            <% } %>

            <form action="${pageContext.request.contextPath}/login" method="POST">
                <div class="form-group">
                    <label class="form-label" for="email">Email Address</label>
                    <input type="email" id="email" name="email" class="form-input" 
                           placeholder="e.g. teacher@attendance.com"
                           value="<%= request.getAttribute("enteredEmail") != null ? request.getAttribute("enteredEmail") : "" %>" required>
                </div>

                <div class="form-group">
                    <label class="form-label" for="password">Password</label>
                    <input type="password" id="password" name="password" class="form-input" 
                           placeholder="Enter your password" required>
                </div>

                <button type="submit" class="btn btn-primary" style="width: 100%; margin-top: 0.5rem;">
                    Sign In to Portal →
                </button>
            </form>

            <div class="demo-credentials">
                <div class="demo-title">⚡ Quick Demo Logins (Click to autofill)</div>
                <button type="button" class="demo-pill" onclick="fillCredentials('teacher@attendance.com', 'teacher123')">
                    👨‍🏫 Teacher (Prof. Ramesh)
                </button>
                <button type="button" class="demo-pill" onclick="fillCredentials('aarav@student.com', 'student123')">
                    👨‍🎓 Student (Aarav)
                </button>
                <button type="button" class="demo-pill" onclick="fillCredentials('priya@student.com', 'student123')">
                    👩‍🎓 Student (Priya)
                </button>
            </div>
        </div>
    </main>

    <footer class="footer">
        <p>Smart Attendance Marking System &copy; 2026 • Java 21 &bull; MySQL 8.0 &bull; Object-Oriented Design</p>
    </footer>

    <script src="${pageContext.request.contextPath}/js/qr-scanner.js"></script>
</body>
</html>
