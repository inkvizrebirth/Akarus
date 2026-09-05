package com.dreamcast.client.shader;

import com.dreamcast.client.DreamcastClient;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Свой «Сатин»: пост-цепочки, собранные в рантайме, а не из JSON.
 *
 * <p>Моды вроде референсного Motion Blur живут на Satin, потому что там есть
 * {@code RuntimeShaderProgram} и свои FBO. В 26.2 этого нет, но есть
 * {@code PostChain.load(PostChainConfig, ...)} с <b>публичными</b> рекордами
 * {@code PostChainConfig}/{@code Pass}/{@code Input}/{@code UniformValue} — то
 * есть конфигурацию цепочки можно построить кодом. Это и есть «свой путь»:
 * тот же механизм, что у ванильных пост-эффектов, но параметры (веса, радиусы,
 * алгоритмы) задаются из модуля, а не печатаются в {@code post_effect/*.json}.</p>
 *
 * <p>Два ограничения, из которых вырос дизайн:</p>
 * <ul>
 *   <li>у {@code PostChain} нет {@code setUniform} — uniform'ы пишутся в UBO при
 *       сборке. Значит «меняем вес каждый кадр» невозможно; меняем тогда саму
 *       цепочку, но только когда значение реально сдвинулось и не чаще, чем раз
 *       в {@value #MIN_REBUILD_INTERVAL_MS} мс;</li>
 *   <li>{@code Projection} и {@code ProjectionMatrixBuffer}, которые требует
 *       {@code PostChain.load}, приватные поля {@code ShaderManager} — лезем за
 *       ними через {@code ShaderManagerMixin}, он же зовёт {@link #resolve}.</li>
 * </ul>
 *
 * <p>Порядок uniform'ов внутри блока — это порядок полей в std140-блоке шейдера:
 * {@code List<UniformValue>} в {@code Pass} не хранит имён, поэтому список надо
 * держать ровно таким же, как объявления в {@code .fsh}. {@code ShaderManagerMixin}
 * пересобирает всё после перезагрузки ресурсов (F3+T) — старые цели к тому моменту
 * уже неактуальны.</p>
 */
public final class PostFx {

	/** Простыня между пересборками: защита от «ползунок дергает FPS-компенсацию». */
	private static final long MIN_REBUILD_INTERVAL_MS = 500L;
	/** Сколько пожить старой цепочке, прежде чем закрыть: GPU должен дорисовать кадр. */
	private static final long RETIRE_AFTER_MS = 500L;

	/** Ванильная цель кадра — вход и финальный выход любой нашей цепочки. */
	public static final String MAIN = "minecraft:main";
	private static final String SCREENQUAD = "minecraft:core/screenquad";
	private static final String BLIT = "minecraft:post/blit";
	private static final String BLIT_BLOCK = "BlitConfig";

	/** Всё, что нужно от {@code ShaderManager}; приходит из миксина. */
	public interface Context {
		TextureManager textures();

		Projection projection();

		ProjectionMatrixBuffer projectionBuffer();
	}

	/**
	 * Внутренняя цель. {@code width}/{@code height} &le; 0 — полный размер экрана
	 * (как отсутствие поля в JSON); {@code persistent} — не сбрасывать между
	 * кадрами (это и есть «история»).
	 */
	public record Target(String id, int width, int height, boolean persistent, int clearColor) {
		public static Target scratch(String id) {
			return new Target(id, 0, 0, false, 0);
		}

		public static Target history(String id) {
			return new Target(id, 0, 0, true, 0);
		}
	}

	/** Сэмплер прохода: откуда читать. {@code depth} — брать depth-буфер цели. */
	public record Input(String samplerName, String target, boolean bilinear, boolean depth) {
		public static Input of(String samplerName, String target) {
			return new Input(samplerName, target, false, false);
		}

		public static Input filtered(String samplerName, String target) {
			return new Input(samplerName, target, true, false);
		}
	}

	/**
	 * Значение uniform'а. Имя — для читаемости конфига, в UBO идёт позиция
	 * в списке (см. javadoc класса).
	 */
	public sealed interface Uniform {
		record Float(String name, float value) implements Uniform {
		}

		record Int(String name, int value) implements Uniform {
		}

		record Vec4(String name, float x, float y, float z, float w) implements Uniform {
		}
	}

	/**
	 * Один проход: шейдеры, входы, выход, блоки uniform'ов.
	 * {@code output == null} — рисовать в {@value #MAIN}.
	 */
	public record Pass(String vertexShader, String fragmentShader, List<Input> inputs,
					   String output, Map<String, List<Uniform>> uniforms) {

		/** Копия кадра как есть — то, чем закрываются история и финальный вывод. */
		public static Pass blit(String input, String output) {
			return new Pass(SCREENQUAD, BLIT, List.of(Input.of("In", input)), output,
					Map.of(BLIT_BLOCK, List.of(new Uniform.Vec4("ColorModulate", 1f, 1f, 1f, 1f))));
		}
	}

	/** Описание цепочки целиком. Рекорд — ради {@code equals}: пересборка только при изменении. */
	public record Spec(List<Target> targets, List<Pass> passes) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {
			private final List<Target> targets = new ArrayList<>();
			private final List<Pass> passes = new ArrayList<>();

			public Builder target(Target target) {
				targets.add(target);
				return this;
			}

			public Builder pass(Pass pass) {
				passes.add(pass);
				return this;
			}

			/**
			 * Проход со своим фрагментным шейдером и одним блоком uniform'ов.
			 * Выход — {@code null} для {@value PostFx#MAIN}.
			 */
			public Builder pass(String fragmentShader, List<Input> inputs, String output,
								String block, List<Uniform> uniforms) {
				passes.add(new Pass(SCREENQUAD, fragmentShader, List.copyOf(inputs), output,
						Map.of(block, List.copyOf(uniforms))));
				return this;
			}

			public Builder blit(String input, String output) {
				passes.add(Pass.blit(input, output));
				return this;
			}

			public Spec build() {
				return new Spec(List.copyOf(targets), List.copyOf(passes));
			}
		}
	}

	private static final class Entry {
		private volatile Spec spec;
		private volatile PostChain chain;
		private volatile PostChain retired;
		private volatile long retiredAt;
		private volatile long lastBuildMs;

		private volatile Spec builtFrom;
	}

	private static final Map<Identifier, Entry> ENTRIES = new LinkedHashMap<>();
	private static volatile Context context;
	private static volatile String lastError = "";
	private static volatile boolean broken;

	private PostFx() {
	}

	/** Миксин отдаёт контекст при первом обращении; с ним цепочки можно собирать вне рендера. */
	public static void setContext(Context newContext) {
		context = newContext;
	}

	public static boolean available() {
		return context != null && !broken;
	}

	/** Последняя ошибка сборки — модуль показывает её один раз вместо молчаливого отката. */
	public static String lastError() {
		return lastError;
	}

	/**
	 * Заявить цепочку. Дёргается из {@code tick()} модуля, поэтому только сравнивает:
	 * сборка происходит в {@link #resolve} на render-потоке.
	 */
	public static void declare(Identifier id, Spec spec) {
		if (broken) {
			return;
		}
		synchronized (ENTRIES) {
			Entry entry = ENTRIES.get(id);
			if (entry == null) {
				entry = new Entry();
				ENTRIES.put(id, entry);
			}
			if (!spec.equals(entry.spec)) {
				entry.spec = spec;
			}
		}
	}

	/** Цепочка наша? (иначе миксин не должен в неё лезть.) */
	public static boolean owns(Identifier id) {
		if (id == null || !DreamcastClient.MOD_ID.equals(id.getNamespace())) {
			return false;
		}
		synchronized (ENTRIES) {
			return ENTRIES.containsKey(id);
		}
	}

	public static void forget(Identifier id) {
		synchronized (ENTRIES) {
			Entry entry = ENTRIES.remove(id);
			if (entry != null) {
				closeQuietly(entry.chain);
				closeQuietly(entry.retired);
				entry.chain = null;
				entry.retired = null;
			}
		}
	}

	/** Цепочка уже собрана и готова — модуль вправе её публиковать как активную. */
	public static boolean isReady(Identifier id) {
		synchronized (ENTRIES) {
			Entry entry = ENTRIES.get(id);
			return entry != null && entry.chain != null;
		}
	}

	/** После перезагрузки ресурсов все цели и шейдеры пересоздаются — сбрасываем и свои. */
	public static void invalidateAll() {
		synchronized (ENTRIES) {
			for (Entry entry : ENTRIES.values()) {
				entry.builtFrom = null;
			}
		}
	}

	/**
	 * Собрать цепочку заранее. Модуль обязан вызвать это из render-потока
	 * (там же, где выбирает {@code postEffectId}), иначе он никогда не увидит
	 * {@link #isReady} и вечно сидел бы на запасном JSON-варианте.
	 */
	public static void prepare(Identifier id) {
		ensureBuilt(id);
	}

	/**
	 * Отдать собранную цепочку (при необходимости — собрать). Вызывается из
	 * {@code ShaderManager#getPostChain} на render-потоке.
	 *
	 * @return {@code null}, если собрать не удалось: ваниль отнесётся к этому
	 *         как к своей же ошибке компиляции пост-цепочки (никакого NPE)
	 */
	public static PostChain resolve(Identifier id) {
		return ensureBuilt(id);
	}

	private static PostChain ensureBuilt(Identifier id) {
		Entry entry;
		Spec spec;
		Context ctx;
		synchronized (ENTRIES) {
			entry = ENTRIES.get(id);
			if (entry == null || entry.spec == null) {
				return null;
			}
			spec = entry.spec;
			ctx = context;
		}
		if (ctx == null) {
			return null;
		}
		if (spec.equals(entry.builtFrom)) {
			retireOld(entry);
			return entry.chain;
		}
		long now = System.currentTimeMillis();
		if (entry.chain != null && now - entry.lastBuildMs < MIN_REBUILD_INTERVAL_MS) {
			return entry.chain; // подождать кадр-другой: ползунок ещё дёргается
		}
		try {
			PostChain built = build(id, spec, ctx);
			synchronized (ENTRIES) {
				closeQuietly(entry.retired);
				entry.retired = entry.chain;
				entry.retiredAt = now;
				entry.chain = built;
				entry.builtFrom = spec;
				entry.lastBuildMs = now;
			}
			lastError = "";
			DreamcastClient.LOGGER.info("Post-chain {} собрана в рантайме ({} passes)", id, spec.passes().size());
			return built;
		} catch (Throwable error) {
			// Один раз сказали — и откатываемся на JSON-цепочку, чтобы не долбить логи каждый кадр.
			broken = true;
			lastError = error.getClass().getSimpleName()
					+ (error.getMessage() == null ? "" : ": " + error.getMessage());
			DreamcastClient.LOGGER.warn("Не удалось собрать пост-цепочку {} — откат на JSON-конфиг: {}",
					id, lastError);
			return null;
		}
	}

	/** Старую цепочку закрываем с задержкой: прошлый кадр мог ещё не дорисоваться. */
	private static void retireOld(Entry entry) {
		if (entry.retired != null && System.currentTimeMillis() - entry.retiredAt > RETIRE_AFTER_MS) {
			PostChain stale = entry.retired;
			entry.retired = null;
			closeQuietly(stale);
		}
	}

	private static PostChain build(Identifier id, Spec spec, Context ctx) throws Exception {
		Map<Identifier, PostChainConfig.InternalTarget> targets = new LinkedHashMap<>();
		for (Target target : spec.targets()) {
			targets.put(id(target.id()), new PostChainConfig.InternalTarget(
					target.width() > 0 ? Optional.of(target.width()) : Optional.empty(),
					target.height() > 0 ? Optional.of(target.height()) : Optional.empty(),
					target.persistent(), target.clearColor()));
		}
		List<PostChainConfig.Pass> passes = new ArrayList<>(spec.passes().size());
		Set<Identifier> external = new LinkedHashSet<>();
		for (Pass pass : spec.passes()) {
			List<PostChainConfig.Input> inputs = new ArrayList<>(pass.inputs().size());
			for (Input input : pass.inputs()) {
				Identifier source = id(input.target());
				if (!targets.containsKey(source)) {
					external.add(source);
				}
				inputs.add(new PostChainConfig.TargetInput(input.samplerName(), source,
						input.depth(), input.bilinear()));
			}
			Identifier output = pass.output() == null ? id(MAIN) : id(pass.output());
			if (!targets.containsKey(output)) {
				external.add(output);
			}
			Map<String, List<UniformValue>> uniforms = new HashMap<>();
			for (Map.Entry<String, List<Uniform>> block : pass.uniforms().entrySet()) {
				List<UniformValue> values = new ArrayList<>(block.getValue().size());
				for (Uniform uniform : block.getValue()) {
					values.add(switch (uniform) {
						case Uniform.Float(String ignored, float value) -> new UniformValue.FloatUniform(value);
						case Uniform.Int(String ignored, int value) -> new UniformValue.IntUniform(value);
						case Uniform.Vec4(String ignored, float x, float y, float z, float w) ->
								new UniformValue.Vec4Uniform(new Vector4f(x, y, z, w));
					});
				}
				uniforms.put(block.getKey(), List.copyOf(values));
			}
			passes.add(new PostChainConfig.Pass(id(pass.vertexShader()), id(pass.fragmentShader()),
					List.copyOf(inputs), output, Map.copyOf(uniforms)));
		}
		if (external.isEmpty()) {
			external.add(id(MAIN));
		}
		return PostChain.load(new PostChainConfig(Map.copyOf(targets), List.copyOf(passes)),
				ctx.textures(), Set.copyOf(external), id, ctx.projection(), ctx.projectionBuffer());
	}

	private static void closeQuietly(PostChain chain) {
		if (chain == null) {
			return;
		}
		try {
			chain.close();
		} catch (Exception ignored) {
			// закрытие не критично: либо уже закрыто, либо контекст GL живёт дольше
		}
	}

	/** Идентификатор из строки {@code ns:path} (без namespace — {@code minecraft}). */
	private static Identifier id(String raw) {
		int colon = raw.indexOf(':');
		if (colon < 0) {
			return Identifier.fromNamespaceAndPath("minecraft", raw);
		}
		return Identifier.fromNamespaceAndPath(raw.substring(0, colon), raw.substring(colon + 1));
	}

	/** Для мода: собрать спецификацию, сравнив которую мы решаем, нужна пересборка. */
	public static Spec motionBlur(String fragmentShader, String historyTarget, String scratchTarget,
								  float blend, float radius, int algorithm) {
		return Spec.builder()
				.target(Target.history(historyTarget))
				.target(Target.scratch(scratchTarget))
				.pass(fragmentShader,
						List.of(Input.filtered("Current", MAIN), Input.filtered("History", historyTarget)),
						scratchTarget, "MotionBlurConfig",
						List.of(new Uniform.Float("BlendFactor", blend),
								new Uniform.Float("SampleRadius", radius),
								new Uniform.Int("BlurAlgorithm", algorithm)))
				.blit(scratchTarget, historyTarget)
				.blit(scratchTarget, MAIN)
				.build();
	}
}
