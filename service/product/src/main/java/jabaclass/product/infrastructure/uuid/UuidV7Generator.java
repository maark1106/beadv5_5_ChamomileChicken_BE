package jabaclass.product.infrastructure.uuid;

import com.github.f4b6a3.uuid.UuidCreator;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;

import java.util.EnumSet;
import java.util.UUID;

public class UuidV7Generator implements BeforeExecutionGenerator {

	@Override
	public UUID generate(SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
		return UuidCreator.getTimeOrderedEpoch();
	}

	@Override
	public EnumSet<EventType> getEventTypes() {
		return EnumSet.of(EventType.INSERT);
	}
}