# Sicherheitshinweise

## Betroffene Versionen

| Version | Status |
| --- | --- |
| v1.2.2 und neuer | aktuell, empfohlen |
| v1.2.1 | **unsicher**, nicht verwenden |
| v1.2.0 | **unsicher**, nicht verwenden |
| v1.1.1 und aelter | nicht betroffen (der Registrierungs-Endpunkt existiert dort nicht) |

Die JAR-Artefakte von v1.2.0 und v1.2.1 wurden aus den GitHub-Releases
entfernt. Die Git-Tags bleiben unveraendert erhalten, damit die Historie
nachvollziehbar bleibt.

## 2026-10-23 - Path Traversal im Scope-Registrierungs-Endpunkt

Betrifft v1.2.0 und v1.2.1, behoben in v1.2.2 (Commit `20ba773`).

Der lokale OAuth-Dienst nimmt unter `POST /internal/scopes` die
Scope-Meldung eines verwalteten Dienstes an. Der `host`-Wert aus dem
Request-Body wurde in einen Dateinamen umgewandelt, **bevor** das Token des
Aufrufers geprueft wurde. Die Zeichenpruefung war ausserdem fehlerhaft
escaped: `contains("\\\\")` sucht im Java-Quelltext nach zwei Backslashes,
ein einzelner passierte sie, und ein Doppelpunkt wurde nicht geprueft.

`Path.resolve` gibt bei einem absoluten Argument genau dieses zurueck und
verwirft die Basis. Ein `host` wie `c:\users\...\x` erzeugte deshalb
`c:\users\...\x.token` und verliess das Datenverzeichnis vollstaendig.

**Auswirkung:** Jeder lokale Prozess - auch unter einem anderen, weniger
privilegierten Windows-Konto - konnte ohne Credential eine Datei an einem
frei gewaehlten Pfad anlegen, mit den Rechten des Benutzers, unter dem der
Webserver laeuft. Der Inhalt ist ein Zufallstoken, die Endung ist auf
`.token` festgelegt; bestehende nicht-leere Dateien wurden nicht
ueberschrieben. Der Dienst lauscht nur auf `127.0.0.1`, eine Ausnutzung aus
dem Netz war nicht moeglich.

**Behebung:** Der Host muss einem reinen DNS-Namen entsprechen (Allowlist),
der aufgeloeste Pfad muss direkt im Registrierungsverzeichnis liegen, und
Validierung sowie Zugehoerigkeit zu einer konfigurierten Central-OAuth-Site
werden geprueft, bevor eine Datei angefasst wird.

## 2026-10-23 - Fail-open bei leerer Scope-Konfiguration

Betrifft v1.2.0 und v1.2.1, behoben in v1.2.2.

War der Berechtigungskatalog einer Site leer, fiel die Freigabe auf das fest
verdrahtete Scope `mcp` zurueck - an drei Stellen: in
`SiteAuthentication.effectiveScopes()`, fuer einen unbekannten Host und fuer
eine fehlende Scope-Liste. Wer Scope-Feld und Katalog leerte, um "nichts
erlauben" auszudruecken, gab damit genau das Scope wieder frei, das zuvor
gesperrt war.

**Behebung:** Keine fest verdrahtete Rueckfallebene mehr. Nichts
konfiguriert heisst nichts anforderbar; `Deny all` ueberschreibt auch den
Basissatz. Jede Anforderung wird dann mit `invalid_scope` abgelehnt.

## 2026-10-23 - Freigaben ohne garantierte Sichtbarkeit

Betrifft v1.2.1, behoben in v1.2.2.

Seit eine reine Scope-Aenderung den OAuth-Dienst nicht mehr neu startet,
wurden die freigegebenen Scopes waehrend laufender Anfragen geschrieben - in
zwei einfachen, nicht `volatile` deklarierten Feldern. Ein Request-Thread
hatte damit keine Garantie, eine gerade entzogene Freigabe zu sehen.

**Behebung:** Beide Angaben liegen als ein unveraenderlicher Snapshot hinter
einem `volatile`-Feld.

## Secrets

Client-Secrets werden ueber die GUI gesetzt und mit Windows DPAPI
geschuetzt gespeichert (`dpapi:`-Prefix). Ein direkt in
`config/webserver.json` geschriebener Klartextwert funktioniert, umgeht aber
den Schutz - er sollte einmal ueber **Einstellungen -> OAuth clients** neu
gesetzt werden. Liegt das Konfigurationsverzeichnis in einem
Cloud-synchronisierten Ordner, ist ein Klartext-Secret auch in der
Versionshistorie des Anbieters; in diesem Fall muss das Secret rotiert
werden, nicht nur geschuetzt.
