package fr.soe.a3s.bridge;

/**
 * Process exit codes for the headless metadata bridge.
 * Unlike the legacy Swing CLI, failures always return non-zero.
 */
public final class BridgeExitCodes {

	public static final int SUCCESS = 0;
	public static final int USAGE = 1;
	public static final int IO_ERROR = 2;
	public static final int DATA_ERROR = 3;

	private BridgeExitCodes() {
	}
}
