const { io } = require("socket.io-client");

const socket = io("http://localhost:3000/chat");

socket.on("connect", () => {
    console.log("connected:", socket.id);

    // 팀 채널 (projectId 없음 - 가장 기본적인 케이스)
    socket.emit("join", { channelId: 1 });

    socket.emit("message", {
        teamId: 1,
        projectId: null,
        channelId: 1,
        authorId: 42,
        content: "hello"
    });
});

socket.on("message", (data) => {
    console.log("📨 received:", data);
});

socket.on("error", (err) => {
    console.error("error:", err);
});

socket.on("connect_error", (err) => {
    console.error("connection error:", err.message);
});
