package jabaclass.product.domain.model.status;

public enum CategoryType {
	SPORTS("운동/스포츠"),
	COOKING("요리"),
	ART("예술/공예"),
	BEAUTY("뷰티"),
	OTHER("기타");

	private final String displayName;

	CategoryType(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}
}