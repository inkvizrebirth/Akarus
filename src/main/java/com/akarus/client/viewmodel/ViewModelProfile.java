package com.akarus.client.viewmodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/**
 * Раскладка рук от первого лица: масштаб, сдвиг и поворот.
 *
 * Один экземпляр на обе руки — именно так работают почти все клиенты: правая и левая
 * рука настраиваются вместе, а «зеркалит» их сама игра (см. {@code invert} в
 * {@code ItemInHandRenderer}). Мы это зеркало повторяем, иначе сдвиг «вправо» для
 * второй руки уезжал бы влево.
 *
 * Важный момент, из-за которого раньше «поворот и размер не работали»: трансформации
 * накладываются не от начала координат, а вокруг запястья — точки, к которой рука
 * «прикреплена» в пространстве экрана. Иначе масштаб уносит руку за край экрана,
 * а поворот закручивает её вокруг угла монитора, и выглядит это ровно как «ничего
 * не работает, только перетаскивание живое».
 */
public class ViewModelProfile {

	/** Точка, вокруг которой рука висит на экране: см. ItemInHandRenderer#applyItemArmTransform. */
	private static final float ANCHOR_X = 0.56F;
	private static final float ANCHOR_Y = -0.52F;
	private static final float ANCHOR_Z = -0.72F;

	/** Какой параметр сейчас редактируется. */
	public enum Parameter {
		SCALE("Масштаб", 0.02f, 0.10f, 3.00f),
		X("Сдвиг X", 0.01f, -1.50f, 1.50f),
		Y("Сдвиг Y", 0.01f, -1.50f, 1.50f),
		Z("Сдвиг Z", 0.01f, -1.50f, 1.50f),
		ROT_X("Поворот X", 1.0f, -90.0f, 90.0f),
		ROT_Y("Поворот Y", 1.0f, -90.0f, 90.0f),
		ROT_Z("Поворот Z", 1.0f, -90.0f, 90.0f);

		private final String displayName;
		private final float step;
		private final float min;
		private final float max;

		Parameter(String displayName, float step, float min, float max) {
			this.displayName = displayName;
			this.step = step;
			this.min = min;
			this.max = max;
		}

		public String getDisplayName() {
			return displayName;
		}

		public float getStep() {
			return step;
		}

		public float getMin() {
			return min;
		}

		public float getMax() {
			return max;
		}

		public boolean isAngle() {
			return this == ROT_X || this == ROT_Y || this == ROT_Z;
		}

		public String format(float value) {
			return isAngle() ? String.format("%.0f°", value) : String.format("%.2f", value);
		}

		public float clamp(float value) {
			return Math.max(min, Math.min(max, value));
		}
	}

	private float scale = 1.0f;
	private float offsetX;
	private float offsetY;
	private float offsetZ;
	private float rotationX;
	private float rotationY;
	private float rotationZ;

	/** Раскладка по умолчанию — ровно так, как рисует ваниль. */
	public static ViewModelProfile createDefault() {
		return new ViewModelProfile();
	}

	/**
	 * Меняет параметр на {@code amount} шагов. Вызывается колесом мыши и стрелками
	 * в редакторе раскладки: amount &gt; 0 — вверх, &lt; 0 — вниз.
	 */
	public void change(Parameter parameter, float amount) {
		set(parameter, get(parameter) + parameter.getStep() * amount);
	}

	public void set(Parameter parameter, float value) {
		float clamped = parameter.clamp(value);
		switch (parameter) {
			case SCALE -> this.scale = clamped;
			case X -> this.offsetX = clamped;
			case Y -> this.offsetY = clamped;
			case Z -> this.offsetZ = clamped;
			case ROT_X -> this.rotationX = clamped;
			case ROT_Y -> this.rotationY = clamped;
			case ROT_Z -> this.rotationZ = clamped;
		}
	}

	public float get(Parameter parameter) {
		return switch (parameter) {
			case SCALE -> scale;
			case X -> offsetX;
			case Y -> offsetY;
			case Z -> offsetZ;
			case ROT_X -> rotationX;
			case ROT_Y -> rotationY;
			case ROT_Z -> rotationZ;
		};
	}

	/**
	 * Накладывает раскладку на текущую позу руки.
	 *
	 * Вызывается из миксина до того, как игра положит на стек взмах и поворот:
	 * наши трансформации оказываются внешними, то есть применяются к руке целиком —
	 * вместе с предметом в ней.
	 *
	 * @param mirrored true — это левая рука, и всё по X отзеркаливается (как в ванили)
	 */
	public void apply(PoseStack poseStack, boolean mirrored) {
		float sign = mirrored ? -1.0f : 1.0f;

		poseStack.pushPose();

		// Сдвиг — в тех же единицах, что и сама рука (1 = блок)
		poseStack.translate(offsetX * sign, offsetY, offsetZ);

		// Масштаб и поворот — вокруг запястья, иначе руку уносит от края экрана
		poseStack.translate(ANCHOR_X * sign, ANCHOR_Y, ANCHOR_Z);
		poseStack.mulPose(Axis.XP.rotationDegrees(rotationX));
		poseStack.mulPose(Axis.YP.rotationDegrees(rotationY));
		poseStack.mulPose(Axis.ZP.rotationDegrees(rotationZ));
		poseStack.scale(scale, scale, scale);
		poseStack.translate(-ANCHOR_X * sign, -ANCHOR_Y, -ANCHOR_Z);
	}

	/** Нужно ли вообще трогать матрицу: при ванильной раскладке этого делать не надо. */
	public boolean isDefault() {
		return Math.abs(scale - 1.0f) < 1.0e-4f
				&& Math.abs(offsetX) < 1.0e-4f && Math.abs(offsetY) < 1.0e-4f && Math.abs(offsetZ) < 1.0e-4f
				&& Math.abs(rotationX) < 1.0e-4f && Math.abs(rotationY) < 1.0e-4f && Math.abs(rotationZ) < 1.0e-4f;
	}

	/** Копия раскладки — для предпросмотра и отмены изменений. */
	public ViewModelProfile copy() {
		ViewModelProfile copy = new ViewModelProfile();
		copy.scale = scale;
		copy.offsetX = offsetX;
		copy.offsetY = offsetY;
		copy.offsetZ = offsetZ;
		copy.rotationX = rotationX;
		copy.rotationY = rotationY;
		copy.rotationZ = rotationZ;
		return copy;
	}

	public void copyFrom(ViewModelProfile other) {
		this.scale = other.scale;
		this.offsetX = other.offsetX;
		this.offsetY = other.offsetY;
		this.offsetZ = other.offsetZ;
		this.rotationX = other.rotationX;
		this.rotationY = other.rotationY;
		this.rotationZ = other.rotationZ;
	}

	public float getScale() {
		return scale;
	}

	public float getOffsetX() {
		return offsetX;
	}

	public float getOffsetY() {
		return offsetY;
	}

	public float getOffsetZ() {
		return offsetZ;
	}

	public float getRotationX() {
		return rotationX;
	}

	public float getRotationY() {
		return rotationY;
	}

	public float getRotationZ() {
		return rotationZ;
	}
}
