package com.high_mobility.HMLink;

/**
 * Created by ttiganik on 13/09/16.
 *
 * This is an evented notification that is sent from the car every time the lock state changes. This
 * notificaiton is also sent when a Get Lock State is received by the car. The new status is included
 * and may be the result of user, device or car triggered action.
 */
public class LockState extends IncomingCommand {
    /**
     * The possible states of the car lock.
     */
    public enum State {
        LOCKED, UNLOCKED
    }

    /**
     *
     * @return the current lock status of the car
     */
    public State getState() {
        return state;
    }

    State state;

    public LockState(byte[] bytes) throws CommandParseException {
        super(bytes);
        if (bytes.length != 3) {
            throw new CommandParseException();
        }

        state = bytes[2] == 0x00 ? State.UNLOCKED : State.LOCKED;
    }
}
