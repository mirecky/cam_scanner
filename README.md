# Skener

Osobná aplikácia pre Android 16 na skenovanie dokumentov s automatickou detekciou listu, automatickým snímaním ďalších listov, OCR (slovenčina, angličtina) a výstupom do vyhľadávateľného PDF. Všetko sa spracúva offline v telefóne.

Podrobný rozsah: [docs/SPEC.md](docs/SPEC.md).

## Inštalácia

1. Na GitHube otvor záložku **Actions** → posledný úspešný beh **Zostavenie APK**.
2. V časti **Artifacts** stiahni `Skener-0.1.N` (ZIP s APK).
3. V telefóne rozbaľ ZIP a otvor APK (pri prvej inštalácii povoľ inštaláciu z neznámych zdrojov pre aplikáciu, ktorou súbor otváraš).

## Podpisový kľúč

Kým v repozitári nie sú nastavené Secrets `SKENER_KEYSTORE_BASE64`, `SKENER_KEYSTORE_PASSWORD`, `SKENER_KEY_ALIAS` a `SKENER_KEY_PASSWORD`, APK sa podpisuje vývojovým kľúčom `keystore/dev.keystore`. Ten je verejný, preto slúži iba na vývoj. Po prechode na súkromný kľúč treba aplikáciu raz odinštalovať a nainštalovať nanovo.

## Zostavenie

```
./gradlew assembleRelease
```

Vyžaduje JDK 17 a Android SDK s platformou 36.
