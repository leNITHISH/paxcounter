import express from "express";
import http from "http";
import { Server } from "socket.io";
import { SerialPort, ReadlineParser } from "serialport";
import fs from "fs";

const app = express();
const server = http.createServer(app);
const io = new Server(server);

app.use(express.static("public"));

const port = new SerialPort({ path: "/dev/ttyUSB1", baudRate: 115200 });
const parser = port.pipe(new ReadlineParser({ delimiter: "\n" }));

// Load OUI CSV
const ouiMap = {};
fs.readFileSync("oui.csv", "utf8")
  .split("\n")
  .forEach((line) => {
    const [prefix, vendor] = line.split(",");
    if (prefix && vendor) ouiMap[prefix.trim().toUpperCase()] = vendor.trim();
  });

function getVendor(mac) {
  const prefix = mac.slice(0, 8).toUpperCase();
  return ouiMap[prefix] || "Unknown";
}

parser.on("data", (line) => {
  try {
    const data = JSON.parse(line);
    if (!data.devices) return;
    data.devices.forEach((d) => (d.vendor = getVendor(d.mac)));
    io.emit("paxdata", data);
  } catch (e) {
    console.log("Bad JSON:", line);
  }
});

io.on("connection", () => console.log("Client connected"));
server.listen(3000, () => console.log("Dashboard at http://localhost:3000"));

