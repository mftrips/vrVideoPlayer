package com.github.mftrips.vrvideoplayer;

import android.util.SparseArray;

import org.metafetish.buttplug.core.ButtplugDeviceMessage;
import org.metafetish.buttplug.core.Messages.FleshlightLaunchFW12Cmd;
import org.metafetish.buttplug.core.Messages.SingleMotorVibrateCmd;
import org.metafetish.buttplug.core.Messages.VorzeA10CycloneCmd;
import org.metafetish.haptic_file_reader.Commands.FunscriptCommand;
import org.metafetish.haptic_file_reader.Commands.HapticCommand;
import org.metafetish.haptic_file_reader.Commands.KiirooCommand;
import org.metafetish.haptic_file_reader.Commands.VorzeCommand;
import org.metafetish.haptic_file_reader.HapticDevice;

import java.util.ArrayList;
import java.util.List;

public class HapticCommandToButtplugMessage {
    public static SparseArray<List<ButtplugDeviceMessage>> hapticCommandToButtplugMessage(List<HapticCommand> commands) {
        SparseArray<List<ButtplugDeviceMessage>> buttplugCommands = new SparseArray<>();
        // Funscript conversion
        //
        // Funscript
        if (commands.get(0) instanceof FunscriptCommand) {
            final SparseArray<List<ButtplugDeviceMessage>> launchCommands =
                    HapticCommandToButtplugMessage.funscriptToFleshlightLaunchCommands(commands);
            final SparseArray<List<ButtplugDeviceMessage>> vibratorCommands =
                        HapticCommandToButtplugMessage.funscriptToSingleMotorVibrateCommands(commands);
            final SparseArray<List<ButtplugDeviceMessage>> vorzeCommands =
                        HapticCommandToButtplugMessage.funscriptToVorzeCommands(commands);
            buttplugCommands = HapticCommandToButtplugMessage.zipCommandMaps(new ArrayList<SparseArray<List<ButtplugDeviceMessage>>>(){{
                add(launchCommands);
                add(vibratorCommands);
                add(vorzeCommands);
            }});
        } else if (commands.get(0) instanceof KiirooCommand) {
            final SparseArray<List<ButtplugDeviceMessage>> launchCommands =
                    HapticCommandToButtplugMessage.kiirooToFleshlightLaunchCommands(commands);
            final SparseArray<List<ButtplugDeviceMessage>> vibratorCommands =
                    HapticCommandToButtplugMessage.kiirooToSingleMotorVibrateCommands(commands);
            buttplugCommands = HapticCommandToButtplugMessage.zipCommandMaps(new ArrayList<SparseArray<List<ButtplugDeviceMessage>>>(){{
                add(launchCommands);
                add(vibratorCommands);
            }});
        } else if (commands.get(0) instanceof VorzeCommand) {
            buttplugCommands = HapticCommandToButtplugMessage.vorzeToVorzeCommands(commands);
        }
        return buttplugCommands;
    }

    // Puts out a sorted command map, with commands at matching times merged.
    private static SparseArray<List<ButtplugDeviceMessage>> zipCommandMaps(List<SparseArray<List<ButtplugDeviceMessage>>> commandMaps) {
        SparseArray<List<ButtplugDeviceMessage>> zipped = new SparseArray<>();
        for (SparseArray<List<ButtplugDeviceMessage>> commands : commandMaps) {
            for (int i = 0; i < commands.size(); ++i) {
                int key = commands.keyAt(i);
                if (zipped.indexOfKey(key) < 0) {
                    zipped.put(key, commands.valueAt(i));
                } else {
                    zipped.get(key).addAll(commands.valueAt(i));
                }
            }
        }
        return zipped;
    }

    // For going from Funscript to Fleshlight Launch, we just follow funjack's
    // code, using the magic algorithm listed at
    //
    // https://godoc.org/github.com/funjack/launchcontrol/protocol/funscript
    private static SparseArray<List<ButtplugDeviceMessage>> funscriptToFleshlightLaunchCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;
        int lastPosition = 0;

        // TODO: We should make a way to change this somehow
        final int range = 90;
        final SparseArray<List<ButtplugDeviceMessage>> commands = new SparseArray<>();
        int currentTime;
        int currentPosition;

        int timeDelta;

        int positionDelta;
        int speed;
        for (HapticCommand hapticCommand : hapticCommands) {
            FunscriptCommand funscriptCommand = (FunscriptCommand) hapticCommand;
            // if this is our first element, save off and continue.
            if (lastTime < 0) {
                lastTime = funscriptCommand.getTime();
                lastPosition = funscriptCommand.getPosition();
                continue;
            }

            currentTime = funscriptCommand.getTime();
            currentPosition = funscriptCommand.getPosition();

            timeDelta = currentTime - lastTime;

            positionDelta = Math.abs(currentPosition - lastPosition);
            if (positionDelta != 0) {
                speed = (int) Math.floor(25000 * Math.pow(((timeDelta * 90) / positionDelta), -1.05));

                // Clamp speed on 20 <= x <= 80 so we don't crash or break the launch.
                final int clampedSpeed = Math.min(Math.max(speed, 5), 90);

                final int positionGoal = (int) Math.floor(((currentPosition / 99) * range) + ((99 - range) / 2));
                // Set movement to happen at the PREVIOUS time, since we're moving toward
                // the goal position with this command, and want to arrive there by the
                // current time.
                commands.put(lastTime, new ArrayList<ButtplugDeviceMessage>() {{
                    add(new FleshlightLaunchFW12Cmd(clampedSpeed, positionGoal));
                }});
            }
            lastTime = funscriptCommand.getTime();
            lastPosition = funscriptCommand.getPosition();
        }
        return commands;
    }

    private static SparseArray<List<ButtplugDeviceMessage>> funscriptToSingleMotorVibrateCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;
        int lastPosition = 0;

        // amount of time (in milliseconds) to put between every interpolated vibration command
        final int density = 75;
        final SparseArray<List<ButtplugDeviceMessage>> commands = new SparseArray<>();

        int timeDelta;

        int timeSteps;
        int posStep;
        int step;

        for (HapticCommand hapticCommand : hapticCommands) {
            FunscriptCommand funscriptCommand = (FunscriptCommand) hapticCommand;
            if (lastTime < 0) {
                lastTime = funscriptCommand.getTime();
                lastPosition = funscriptCommand.getPosition();
                continue;
            }
            final int currentTime = funscriptCommand.getTime();
            final int currentPosition = funscriptCommand.getPosition();

            timeDelta = currentTime - lastTime;
            if (timeDelta > 0) {
                // Set a maximum time delta, otherwise we'll have ramps that can last
                // multiple minutes.
                if (timeDelta > 5000) {
                    timeDelta = 5000;
                    commands.put(lastTime + timeDelta + 1, new ArrayList<ButtplugDeviceMessage>(){{
                        add(new SingleMotorVibrateCmd(0));
                    }});
                }

                timeSteps = (int) Math.floor(timeDelta / density);
                if (timeSteps > 0) {
                    posStep = ((currentPosition - lastPosition) / 100) / timeSteps;
                    step = 0;
                    while (lastTime + (step * density) < currentTime) {
                        final double stepPos = (lastPosition * 0.01) + (posStep * step);
                        commands.put(lastTime + (step * density), new ArrayList<ButtplugDeviceMessage>(){{
                            add(new SingleMotorVibrateCmd(stepPos));
                        }});
                        step += 1;
                    }
                } else {
                    commands.put(currentTime, new ArrayList<ButtplugDeviceMessage>(){{
                        add(new SingleMotorVibrateCmd(currentPosition / 100));
                    }});
                }
            }
            lastTime = currentTime;
            lastPosition = currentPosition;
        }
        // Make sure we stop the vibrator at the end
        commands.put(lastTime + 100, new ArrayList<ButtplugDeviceMessage>(){{
            add(new SingleMotorVibrateCmd(0));
        }});
        return commands;
    }

    private static SparseArray<List<ButtplugDeviceMessage>> funscriptToVorzeCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;
        int lastPosition = 0;

        int currentTime;
        int currentPosition;
        final SparseArray<List<ButtplugDeviceMessage>> commands = new SparseArray<>();

        int timeDelta;
        int positionDelta;

        for (HapticCommand hapticCommand : hapticCommands) {
            FunscriptCommand funscriptCommand = (FunscriptCommand) hapticCommand;
            currentTime = funscriptCommand.getTime();
            currentPosition = funscriptCommand.getPosition();

            timeDelta = currentTime - lastTime;
            // If more than a certain amount of time exists between 2 commands, add a command to stop after the last
            if (timeDelta > 10000) {
                commands.put(lastTime + timeDelta + 1, new ArrayList<ButtplugDeviceMessage>(){{
                    add(new VorzeA10CycloneCmd(0, true));
                }});
            }

            // We still need to calculate the Launch speed from the commands to set vorze speed.
            positionDelta = Math.abs(currentPosition - lastPosition);
            if (positionDelta > 0) {
                final int speed = (int) Math.floor(25000 * Math.pow(((timeDelta * 90) / positionDelta), -1.05));
                final int clampedSpeed = Math.min(Math.max(speed, 0), 99);
                final boolean clockwise = lastPosition > currentPosition;
                commands.put(currentTime, new ArrayList<ButtplugDeviceMessage>(){{
                    add(new VorzeA10CycloneCmd(clampedSpeed, clockwise));
                }});
            }
            lastTime = currentTime;
            lastPosition = currentPosition;
        }
        // Make sure we stop the Vorze at the end
        commands.put(lastTime + 100, new ArrayList<ButtplugDeviceMessage>(){{
            add(new VorzeA10CycloneCmd(0, true));
        }});
        return commands;
    }

    private static SparseArray<List<ButtplugDeviceMessage>> kiirooToFleshlightLaunchCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;
        int lastSpeed = 0;
        int currentTime;
        int currentSpeed = 0;
        int timeDelta;
        int limitedSpeed = 0;
        final SparseArray<List<ButtplugDeviceMessage>> commands = new SparseArray<>();
        for (HapticCommand hapticCommand : hapticCommands) {
            KiirooCommand kiirooCommand = (KiirooCommand) hapticCommand;
            HapticDevice commandDevice = kiirooCommand.getDevice();
            if (commandDevice != HapticDevice.ANY && commandDevice != HapticDevice.LINEAR) {
                continue;
            }
            // if this is our first element, save off and continue.
            if (lastTime < 0) {
                lastTime = kiirooCommand.getTime();
                continue;
            }
            currentTime = kiirooCommand.getTime();
            final int currentPosition = kiirooCommand.getPosition();

            timeDelta = currentTime - lastTime;

            if (timeDelta > 2000) {
                currentSpeed = 50;
            } else if (timeDelta > 1000) {
                currentSpeed = 20;
            } else {
                currentSpeed = (int) (100 - ((currentSpeed / 100) + (currentSpeed / 100 * .1)));
                if (currentSpeed > lastSpeed) {
                    currentSpeed = lastSpeed + ((currentSpeed - lastSpeed) / 6);
                } else {
                    currentSpeed = lastSpeed - (currentSpeed / 2);
                }
            }
            currentSpeed = (int) Math.floor(currentSpeed);
            if (currentSpeed < 20) {
                currentSpeed = 20;
            }

            lastTime = kiirooCommand.getTime();
            lastSpeed = currentSpeed;

            final int newSpeed;
            if (timeDelta <= 150) {
                if (limitedSpeed == 0) {
                    limitedSpeed = currentSpeed;
                }
                newSpeed = limitedSpeed;
            } else {
                limitedSpeed = 0;
                newSpeed = currentSpeed;
            }
            commands.put(lastTime, new ArrayList<ButtplugDeviceMessage>(){{
                add(new FleshlightLaunchFW12Cmd(newSpeed, currentPosition > 2 ? 5 : 95));
            }});
        }
        return commands;
    }

    private static SparseArray<List<ButtplugDeviceMessage>> kiirooToSingleMotorVibrateCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;
        int lastPosition = 0;

        // amount of time (in milliseconds) to put between every interpolated vibration command
        final int density = 75;
        final SparseArray<List<ButtplugDeviceMessage>> commands = new SparseArray<>();

        int currentTime;
        int currentPosition;

        int timeDelta;

        int timeSteps;
        int posStep;
        int step;

        for (HapticCommand hapticCommand : hapticCommands) {
            KiirooCommand kiirooCommand = (KiirooCommand) hapticCommand;
            HapticDevice commandDevice = kiirooCommand.getDevice();
            if (commandDevice != HapticDevice.ANY && commandDevice != HapticDevice.VIBRATE) {
                continue;
            }
            if (lastTime == 0) {
                lastTime = kiirooCommand.getTime();
                lastPosition = 100 - (kiirooCommand.getPosition() * 25);
                continue;
            }
            currentTime = kiirooCommand.getTime();
            // Convert to 0-100
            currentPosition = 100 - (kiirooCommand.getPosition() * 25);

            timeDelta = currentTime - lastTime;

            // Set a maximum time delta, otherwise we'll have ramps that can last
            // multiple minutes.
            if (timeDelta > 5000) {
                timeDelta = 5000;
                commands.put(lastTime + timeDelta + 1, new ArrayList<ButtplugDeviceMessage>(){{
                    add(new SingleMotorVibrateCmd(0));
                }});
            }

            timeSteps = (int) Math.max(Math.floor(timeDelta / density), 1);
            posStep = ((currentPosition - lastPosition) / 100) / timeSteps;
            step = 0;
            while (lastTime + (step * density) < currentTime) {
                final double stepPos = (lastPosition * 0.01) + (posStep * step);
                commands.put(lastTime + (step * density), new ArrayList<ButtplugDeviceMessage>(){{
                    add(new SingleMotorVibrateCmd(stepPos));
                }});
                step += 1;
            }
            lastTime = currentTime;
            lastPosition = currentPosition;
        }
        // Make sure we stop the vibrator at the end
        commands.put(lastTime + 100, new ArrayList<ButtplugDeviceMessage>(){{
            add(new SingleMotorVibrateCmd(0));
        }});
        return commands;
    }

    private static SparseArray<List<ButtplugDeviceMessage>> vorzeToVorzeCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;

        int currentTime;

        final SparseArray<List<ButtplugDeviceMessage>> commands = new SparseArray<>();

        for (HapticCommand hapticCommand : hapticCommands) {
            final VorzeCommand vorzeCommand = (VorzeCommand) hapticCommand;
            currentTime = vorzeCommand.getTime();
            commands.put(currentTime, new ArrayList<ButtplugDeviceMessage>(){{
                add(new VorzeA10CycloneCmd(vorzeCommand.getSpeed(), vorzeCommand.getDirection() != 0));
            }});
            lastTime = currentTime;
        }
        // Make sure we stop the Vorze at the end
        commands.put(lastTime + 100, new ArrayList<ButtplugDeviceMessage>(){{
            add(new VorzeA10CycloneCmd(0, true));
        }});
        return commands;
    }
}
