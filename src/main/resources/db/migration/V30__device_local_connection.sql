-- V30: where to reach a device on the local network and its PIN, kept in Stocka (25-10-2026).
--
-- The Pi never has Internet at the belén: the editor talks to it over the LAN (its own Wi-Fi or a phone
-- hotspot) at an address such as http://belen.local:8080 and sends commands with the 6-digit PIN printed on
-- the box. Until now each browser remembered both on its own; storing them on the device row lets every
-- computer and phone of the organization find the Pi already configured. The PIN is kept in clear because
-- the editor has to send it to the Pi; the API only shows it to members who may send shows.

ALTER TABLE devices ADD COLUMN local_url VARCHAR(255) NULL AFTER board;
ALTER TABLE devices ADD COLUMN pin VARCHAR(16) NULL AFTER local_url;
