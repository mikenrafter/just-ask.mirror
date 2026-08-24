# Route Gradle through the FHS env so aapt2/JDK resolve on NixOS.
NIX := nix develop --command
FHS := just-ask-fhs

.PHONY: assemble install

assemble:
	$(NIX) $(FHS) ./gradlew :app:assembleDebug

install:
	$(NIX) $(FHS) ./gradlew :app:installDebug
