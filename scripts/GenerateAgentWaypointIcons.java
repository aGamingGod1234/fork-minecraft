import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Regenerates locator-bar face sprites and styles from the authoritative visual manifest. */
public final class GenerateAgentWaypointIcons {
	private static final Pattern VARIANT = Pattern.compile(
			"\\\"texturePath\\\"\\s*:\\s*\\\"arenaagents:textures/entity/([^\\\"]+)\\.png\\\"\\s*,\\s*"
					+ "\\\"transportCode\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

	private GenerateAgentWaypointIcons() { }

	public static void main(String[] arguments) throws Exception {
		Path project = arguments.length == 0 ? Path.of("").toAbsolutePath() : Path.of(arguments[0]).toAbsolutePath();
		Path resources = project.resolve("src/main/resources/assets/arenaagents");
		String manifest = Files.readString(resources.resolve("identity/agent_visual_manifest.json"), StandardCharsets.UTF_8);
		Path spriteRoot = resources.resolve("textures/gui/sprites/hud/locator_bar_dot/agent");
		Path styleRoot = resources.resolve("waypoint_style/agent");
		Files.createDirectories(spriteRoot);
		Files.createDirectories(styleRoot);
		Matcher variants = VARIANT.matcher(manifest);
		Set<String> codes = new HashSet<>();
		while (variants.find()) {
			String textureName = variants.group(1);
			String code = variants.group(2).toLowerCase();
			if (!codes.add(code)) throw new IllegalStateException("Duplicate transport code: " + code);
			BufferedImage skin = ImageIO.read(resources.resolve("textures/entity/" + textureName + ".png").toFile());
			if (skin == null || skin.getWidth() != skin.getHeight() || skin.getWidth() % 64 != 0) {
				throw new IllegalStateException("Expected a square 64x64-compatible skin: " + textureName);
			}
			int scale = skin.getWidth() / 64;
			BufferedImage head = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = head.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			graphics.drawImage(skin.getSubimage(8 * scale, 8 * scale, 8 * scale, 8 * scale), 0, 0, 16, 16, null);
			graphics.setComposite(AlphaComposite.SrcOver);
			graphics.drawImage(skin.getSubimage(40 * scale, 8 * scale, 8 * scale, 8 * scale), 0, 0, 16, 16, null);
			graphics.dispose();
			ImageIO.write(head, "png", spriteRoot.resolve(code + ".png").toFile());
			Files.writeString(styleRoot.resolve(code + ".json"),
					"{\n  \"sprites\": [\"arenaagents:agent/" + code + "\"]\n}\n", StandardCharsets.UTF_8);
		}
		if (codes.size() != 64) throw new IllegalStateException("Expected 64 variants, found " + codes.size());
	}
}
