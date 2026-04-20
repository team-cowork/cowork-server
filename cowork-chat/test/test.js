const { io } = require("socket.io-client");

const socket = io("http://localhost:3000");

socket.on("connect", () => {
    console.log("✅ connected");

    socket.emit("message", {
        content: "hello"
    });
});

socket.on("message", (data) => {
    console.log("📨 received:", data);
});

socket.on("connect_error", (err) => {
    console.error("❌ connection error:", err.message);
});
