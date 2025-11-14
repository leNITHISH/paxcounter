import express from "express";
import http from "http";
import { Server } from "socket.io";
import { SerialPort, ReadlineParser } from "serialport";
import fs from "fs";

const app = express();
const server = http.createServer(app);
const io = new Server(server);

app.use(express.static("public"));

const port = new SerialPort({ path: "/dev/ttyUSB0", baudRate: 115200 });
const parser = port.pipe(new ReadlineParser({ delimiter: "\n" }));

// Load OUI CSV - properly parse CSV with quoted fields
const ouiMap = {};
function parseCSVLine(line) {
  const result = [];
  let current = '';
  let inQuotes = false;
  
  for (let i = 0; i < line.length; i++) {
    const char = line[i];
    if (char === '"') {
      inQuotes = !inQuotes;
    } else if (char === ',' && !inQuotes) {
      result.push(current.trim());
      current = '';
    } else {
      current += char;
    }
  }
  result.push(current.trim());
  return result;
}

const lines = fs.readFileSync("oui.csv", "utf8").split("\n");
// Skip header line (line 0)
for (let i = 1; i < lines.length; i++) {
  const line = lines[i].trim();
  if (!line) continue;
  
  const cols = parseCSVLine(line);
  // Format: Registry, Assignment (MAC prefix), Organization Name, Organization Address
  if (cols.length >= 3) {
    const prefix = cols[1].trim().toUpperCase();
    const vendor = cols[2].trim();
    if (prefix && vendor && prefix.length === 6) {
      ouiMap[prefix] = vendor;
    }
  }
}

function getVendor(mac) {
  // Extract first 6 hex characters from MAC (OUI is 3 bytes = 6 hex chars)
  // Handle formats: "AA:BB:CC:DD:EE:FF" or "AABBCCDDEEFF"
  const cleanMac = mac.replace(/[:-]/g, '').toUpperCase();
  const prefix = cleanMac.slice(0, 6);
  return ouiMap[prefix] || "Randomized";
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

