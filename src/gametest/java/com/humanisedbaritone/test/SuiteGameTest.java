package com.humanisedbaritone.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Entry point of all in-game tests. Choose the suite with the HB_TEST environment variable: move, task, build, combat, terrain, nature, life, human, gui, surface, clutch, feel, feelnature, style, scan, scanmap, elytra, elytranature. */
public class SuiteGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.options.pauseOnLostFocus = false); // the test window loses focus whenever somebody uses the PC: vanilla then opens the pause menu, the game stops ticking and the test waits for ever
		String which = System.getenv("HB_TEST");
		FabricClientGameTest t = switch (which == null ? "move" : which) {
			case "task" -> new TaskGameTest();
			case "build" -> new BuildGameTest();
			case "pickle" -> new PickleGameTest();
			case "preview" -> new PreviewGameTest();
			case "schematic" -> new SchematicGuiGameTest();
			case "human" -> new HumanGameTest();
			case "gui" -> new GuiGameTest();
			case "profile" -> new ProfileGameTest();
			case "chat" -> new ChatGameTest();
			case "remember" -> new RememberGameTest();
			case "style" -> new StyleGameTest();
			case "scan" -> new ScanGameTest();
			case "scanmap" -> new ScanMapGameTest();
			case "combat" -> new CombatGameTest();
			case "terrain" -> new TerrainGameTest();
			case "nature" -> new NatureGameTest();
			case "life" -> new LifeGameTest();
			case "clutch" -> new ClutchGameTest();
			case "elytra" -> new ElytraGameTest();
			case "elytranature" -> new ElytraNatureGameTest();
			case "surface" -> new SurfaceGameTest();
				case "feel" -> new FeelGameTest();
				case "feelnature" -> new FeelNatureGameTest();
			default -> new MoveGameTest();
		};
		t.runTest(ctx);
	}
}
