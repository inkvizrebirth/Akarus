package com.akarus.client.viewmodel;

/**
 * Раскладка рук от первого лица: масштаб, сдвиг и поворот по трём осям.
 *
 * Один экземпляр на обе руки — именно так работают почти все клиенты:
 * правая и левая рука настраиваются вместе, а «зеркалит» их сама игра.
 */
public class ViewModelProfile {

	/** Какой параметр сейчас редактируется колесом мыши. */
	public enum Parameter {
		SCALE("Scale", 0.02f, 0.10f, 4.00f),
		X("X", 0.02f, -3.00f, 3.00f),
		Y("Y", 0.02f, -3.00f, 3.00f),
		Z("Z", 0.02f, -3.00f, 3.00f),
		ROT_X("Поворот X", 1.0f, -180.0f, 180.0f),
		ROT_Y("Поворот Y", 1.0f, -180.0f, 180.0f),
		ROT_Z("Поворот Z", 1.0f, -180.0f, 180.0f);

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
	 * Меняет параметр на {@code amount} шагов. Вызывается колесом мыши
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

	/** Копия раскладки — для превью и отмены изменений. */
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

	/** Ровно ли совпадает с ванильной раскладкой. */
	public boolean isDefault() {
		return Math.abs(scale - 1.0f) < 1.0e-4f
				&& Math.abs(offsetX) < 1.0e-4f && Math.abs(offsetY) < 1.0e-4f && Math.abs(offsetZ) < 1.0e-4f
				&& Math.abs(rotationX) < 1.0e-4f && Math.abs(rotationY) < 1.0e-4f && Math.abs(rotationZ) < 1.0e-4f;
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
