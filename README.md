# Skener

Osobná aplikácia pre Android 16 na skenovanie dokumentov s automatickou detekciou listu, automatickým snímaním ďalších listov, OCR (slovenčina, angličtina) a výstupom do vyhľadávateľného PDF. Všetko sa spracúva offline v telefóne.

Podrobný rozsah: [docs/SPEC.md](docs/SPEC.md).

## Inštalácia

1. V telefóne otvor https://github.com/mirecky/cam_scanner/releases/tag/najnovsie a stiahni súbor `Skener-0.1.N.apk`.
2. Otvor stiahnutý APK a potvrď inštaláciu. Pri prvej inštalácii treba povoliť inštaláciu z neznámych zdrojov pre prehliadač.

## Podpisový kľúč

Kým v repozitári nie sú nastavené Secrets `SKENER_KEYSTORE_BASE64`, `SKENER_KEYSTORE_PASSWORD`, `SKENER_KEY_ALIAS` a `SKENER_KEY_PASSWORD`, APK sa podpisuje vývojovým kľúčom `keystore/dev.keystore`. Ten je verejný, preto slúži iba na vývoj. Po prechode na súkromný kľúč treba aplikáciu raz odinštalovať a nainštalovať nanovo.

## Zostavenie

```
./gradlew assembleRelease
```

Vyžaduje JDK 17 a Android SDK s platformou 36.
