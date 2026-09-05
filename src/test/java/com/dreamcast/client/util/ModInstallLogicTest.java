package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила автоустановки мода: что качать, когда не качать и чем является
 * скачанный файл. Сеть и диск в тесте не проверяются — но именно здесь
 * решается, не положим ли мы в {@code mods/} Forge-сборку или HTML-ошибку.
 */
class ModInstallLogicTest {

	@Test
	void picksFabricVariant() {
		List<String> files = List.of("baritone-forge-1.21.8-21.jar", "baritone-fabric-1.21.8-21.jar");
		assertEquals("baritone-fabric-1.21.8-21.jar", ModInstallLogic.pickFile(files));
	}

	@Test
	void ignoresSourcesAndNonJar() {
		List<String> files = List.of("baritone-sources.jar", "baritone-fabric.jar", "readme.txt");
		assertEquals("baritone-fabric.jar", ModInstallLogic.pickFile(files));
	}

	@Test
	void fallsBackToFirstJarWhenNoFabric() {
		assertEquals("baritone-neoforge-1.0.jar",
				ModInstallLogic.pickFile(List.of("baritone-neoforge-1.0.jar", "notes.txt")));
		assertNull(ModInstallLogic.pickFile(List.of()), "пустой список — ставить нечего");
		assertNull(ModInstallLogic.pickFile(null));
	}

	@Test
	void decidesState() {
		assertEquals(ModInstallLogic.Decision.LOADED, ModInstallLogic.decide(true, false, true),
				"мод уже в игре — и трогать нечего");
		assertEquals(ModInstallLogic.Decision.LOADED, ModInstallLogic.decide(true, true, false),
				"загруженный мод важнее любых настроек");
		assertEquals(ModInstallLogic.Decision.NEEDS_RESTART, ModInstallLogic.decide(false, true, true),
				"файл есть, игры нет — нужен перезапуск, а не новое скачивание");
		assertEquals(ModInstallLogic.Decision.DOWNLOAD, ModInstallLogic.decide(false, false, true));
		assertEquals(ModInstallLogic.Decision.DISABLED, ModInstallLogic.decide(false, false, false));
	}

	@Test
	void recognizesZipHeader() {
		assertTrue(ModInstallLogic.looksLikeJar('P', 'K', 3, 4), "обычный jar");
		assertTrue(ModInstallLogic.looksLikeJar('P', 'K', 5, 6), "пустой архив");
		assertFalse(ModInstallLogic.looksLikeJar('<', '?', 'x', 'm'), "HTML-страница ошибки");
		assertFalse(ModInstallLogic.looksLikeJar('P', 'K', 0, 0), "мусор с зачатками PK");
	}

	@Test
	void findsModInDirectory() {
		assertTrue(ModInstallLogic.hasModFile(List.of("sodium.jar", "Baritone-Fabric-21.jar"), "baritone"));
		assertFalse(ModInstallLogic.hasModFile(List.of("sodium.jar", "baritone-notes.txt"), "baritone"),
				"не-jar рядом с модом — не признак установленного мода");
		assertFalse(ModInstallLogic.hasModFile(List.of(), "baritone"));
		assertFalse(ModInstallLogic.hasModFile(null, "baritone"));
	}

	@Test
	void buildsModrinthQuery() {
		assertEquals("loaders=[\"fabric\"]&game_versions=%5B%2226.2%22%5D",
				ModInstallLogic.modrinthQuery("26.2"));
		assertEquals("loaders=[\"fabric\"]", ModInstallLogic.modrinthQuery(null),
				"без версии — фильтр не добавляем, иначе Modrinth ответит 400");
		assertEquals("loaders=[\"fabric\"]&game_versions=%5B%2226.2%22%5D",
				ModInstallLogic.modrinthQuery(" 26.2 \"[] "), "кавычки и скобки из ввода вырезаются");
	}
}
