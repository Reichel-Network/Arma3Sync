package fr.soe.a3s.bridge;

/**
 * JSON / domain mapping failure for the headless metadata bridge.
 * Mapped to {@link BridgeExitCodes#DATA_ERROR}.
 */
public final class BridgeDataException extends RuntimeException {

	public BridgeDataException(String message) {
		super(message);
	}

	public BridgeDataException(String message, Throwable cause) {
		super(message, cause);
	}
}
