# Changelog

## 0.2.1

- Fix: Dashboard und btw-Fenster starteten in CLion 2026.3 nicht (`NoClassDefFoundError: JBCefApp`). Seit 2026.x ist
  der eingebettete Browser ein eigenes Plugin; das Plugin erklärt jetzt eine optionale Abhängigkeit darauf und zeigt
  eine Textansicht, falls das Plugin „Web Browser (JCEF)“ deaktiviert ist.

## 0.2.0

Erste Version für CLion (und alle anderen JetBrains-IDEs ab 2025.2). Sie hat den Funktionsumfang
der VS-Code-Erweiterung [Copilot Contingent Watcher 0.2.0](https://github.com/m3-13/vsc-contingent-watcher).

- Statusleiste: Rest, Verbrauch, Prozent oder Tagesbudget, mit Füllstands-Icon (Tachometer, Batterie, Ring, Balken),
  eigener Farbe und Warnhintergrund; Tooltip mit Balken, Tagesbudget, Tempo, Prognose und Spartipp
- Dashboard als Toolwindow mit 4 Tabs (Übersicht, Verlauf, Tipps, Einstellungen); dieselbe Oberfläche wie in
  VS Code, im eingebetteten Browser (JCEF) und mit den Farben des IDE-Themes
- btw-Fenster für Nebenfragen (`Strg+Alt+Umschalt+B`, Kontextmenü „btw: Auswahl erklären“): Kontext aus Editor,
  Auswahl und Problemen, optional einer lokalen Chat-Sitzung; Chat-Modell, einmal gewähltes Modell oder Gratis-Modell
- Warnungen bei Schwellenwerten, Tempo und drohendem Verfall; 24 Stunden stummschalten
- Verlauf: aktive Zeit und Verbrauch pro Git-Branch, Monatsrückblick, Verbrauch pro Modell (optional mit Token)
- AI-Credits oder Premium-Requests, Bedarf (geschätzt), Tagesbudget ohne Wochenenden und mit Reserve
- Anmeldung über das GitHub-Copilot-Plugin, ein GitHub-Konto der IDE oder einen Token; GHE.com und Enterprise Server
- Einstellungsseite unter *Settings › Tools › Copilot Contingent Watcher*, Erste-Schritte-Dialog, Log-Ansicht
- Deutsch und Englisch (folgt der IDE-Sprache oder fest einstellbar)
