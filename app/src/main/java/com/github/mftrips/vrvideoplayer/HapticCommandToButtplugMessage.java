package com.github.mftrips.vrvideoplayer;

import android.support.v4.util.SparseArrayCompat;

import org.metafetish.buttplug.core.ButtplugDeviceMessage;
import org.metafetish.buttplug.core.Messages.FleshlightLaunchFW12Cmd;
import org.metafetish.buttplug.core.Messages.SingleMotorVibrateCmd;
import org.metafetish.buttplug.core.Messages.VorzeA10CycloneCmd;
import org.metafetish.haptic_file_reader.Commands.FunscriptCommand;
import org.metafetish.haptic_file_reader.Commands.HapticCommand;
import org.metafetish.haptic_file_reader.Commands.KiirooCommand;
import org.metafetish.haptic_file_reader.Commands.VorzeCommand;
import org.metafetish.haptic_file_reader.HapticDevice;
import org.metafetish.haptic_file_reader.Properties.FunscriptProperties;
import org.metafetish.haptic_file_reader.Properties.HapticProperties;

import java.util.ArrayList;
import java.util.List;

public class HapticCommandToButtplugMessage {
    public static int maxDelta = (int) Math.floor(Math.pow(0.2 / 250, -0.95) / 0.9);

    public static SparseArrayCompat<List<ButtplugDeviceMessage>> hapticCommandToButtplugMessage(List<HapticCommand> commands, HapticProperties properties) {
        SparseArrayCompat<List<ButtplugDeviceMessage>> buttplugCommands = new SparseArrayCompat<>();
        // Funscript conversion
        //
        // Funscript
        if (commands.get(0) instanceof FunscriptCommand) {
            FunscriptProperties funscriptProperties = null;
            if (properties instanceof FunscriptProperties) {
                funscriptProperties = (FunscriptProperties) properties;
            }
            final SparseArrayCompat<List<ButtplugDeviceMessage>> launchCommands =
                    HapticCommandToButtplugMessage.funscriptToFleshlightLaunchCommands(commands, funscriptProperties);
            final SparseArrayCompat<List<ButtplugDeviceMessage>> vibratorCommands =
                        HapticCommandToButtplugMessage.funscriptToSingleMotorVibrateCommands(commands, funscriptProperties);
            final SparseArrayCompat<List<ButtplugDeviceMessage>> vorzeCommands =
                        HapticCommandToButtplugMessage.funscriptToVorzeCommands(commands, funscriptProperties);
            buttplugCommands = HapticCommandToButtplugMessage.zipCommandMaps(new ArrayList<SparseArrayCompat<List<ButtplugDeviceMessage>>>(){{
                add(launchCommands);
                add(vibratorCommands);
                add(vorzeCommands);
            }});
        } else if (commands.get(0) instanceof KiirooCommand) {
            final SparseArrayCompat<List<ButtplugDeviceMessage>> launchCommands =
                    HapticCommandToButtplugMessage.kiirooToFleshlightLaunchCommands(commands);
            final SparseArrayCompat<List<ButtplugDeviceMessage>> vibratorCommands =
                    HapticCommandToButtplugMessage.kiirooToSingleMotorVibrateCommands(commands);
            buttplugCommands = HapticCommandToButtplugMessage.zipCommandMaps(new ArrayList<SparseArrayCompat<List<ButtplugDeviceMessage>>>(){{
                add(launchCommands);
                add(vibratorCommands);
            }});
        } else if (commands.get(0) instanceof VorzeCommand) {
            buttplugCommands = HapticCommandToButtplugMessage.vorzeToVorzeCommands(commands);
        }
        return buttplugCommands;
    }

    // Puts out a sorted command map, with commands at matching times merged.
    private static SparseArrayCompat<List<ButtplugDeviceMessage>> zipCommandMaps(List<SparseArrayCompat<List<ButtplugDeviceMessage>>> commandMaps) {
        SparseArrayCompat<List<ButtplugDeviceMessage>> zipped = new SparseArrayCompat<>();
        for (SparseArrayCompat<List<ButtplugDeviceMessage>> commands : commandMaps) {
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
    private static SparseArrayCompat<List<ButtplugDeviceMessage>> funscriptToFleshlightLaunchCommands(List<HapticCommand> hapticCommands, FunscriptProperties properties) {
        int lastTime = 0;
        int lastPosition = 0;

        int range = 90;
        boolean inverted = false;
        if (properties != null) {
            range = properties.getRange();
            inverted = properties.isInverted();
        }
        final SparseArrayCompat<List<ButtplugDeviceMessage>> commands = new SparseArrayCompat<>();
        int currentTime;
        int currentPosition;

        int timeDelta;

        int positionDelta;
        int speed;
        for (HapticCommand hapticCommand : hapticCommands) {
            FunscriptCommand funscriptCommand = (FunscriptCommand) hapticCommand;

            currentTime = funscriptCommand.getTime();
            currentPosition = inverted ? 100 - funscriptCommand.getPosition() : funscriptCommand.getPosition();

            timeDelta = currentTime - lastTime;

            positionDelta = Math.abs(currentPosition - lastPosition);
            if (positionDelta != 0) {
                speed = (int) Math.floor(25000 * Math.pow((double) (timeDelta * 90) / positionDelta, -1.05));

                // Clamp speed on 20 <= x <= 80 so we don't crash or break the launch.
                final int clampedSpeed = Math.min(Math.max(speed, 5), 90);

                final int positionGoal = (int) Math.floor((((double) currentPosition / 100) * range) + ((double) (100 - range) / 2));
                // Set movement to happen at the PREVIOUS time, since we're moving toward
                // the goal position with this command, and want to arrive there by the
                // current time.
                commands.put(lastTime, new ArrayList<ButtplugDeviceMessage>() {{
                    add(new FleshlightLaunchFW12Cmd(clampedSpeed, positionGoal));
                }});
            }
            lastTime = funscriptCommand.getTime();
            lastPosition = inverted ? 100 - funscriptCommand.getPosition() : funscriptCommand.getPosition();
        }
        return commands;
    }

    private static SparseArrayCompat<List<ButtplugDeviceMessage>> funscriptToSingleMotorVibrateCommands(List<HapticCommand> hapticCommands, FunscriptProperties properties) {
        int lastTime = 0;
        int lastPosition = 0;

        // amount of time (in milliseconds) to put between every interpolated vibration command
        final int density = 75;
        final SparseArrayCompat<List<ButtplugDeviceMessage>> commands = new SparseArrayCompat<>();

        int timeDelta;

        int timeSteps;
        double posStep;
        int step;

        for (HapticCommand hapticCommand : hapticCommands) {
            FunscriptCommand funscriptCommand = (FunscriptCommand) hapticCommand;

            final int currentTime = funscriptCommand.getTime();
            final int currentPosition = funscriptCommand.getPosition();

            timeDelta = currentTime - lastTime;
            if (timeDelta > 0) {
                // Set a maximum time delta, otherwise we'll have ramps that can last multiple minutes.
                if (timeDelta > HapticCommandToButtplugMessage.maxDelta) {
                    timeDelta = HapticCommandToButtplugMessage.maxDelta - density;
                    commands.put(lastTime + timeDelta, new ArrayList<ButtplugDeviceMessage>(){{
                        add(new SingleMotorVibrateCmd(0));
                    }});
                }

                timeSteps = (int) Math.floor((double) timeDelta / density);
                if (timeSteps > 0) {
                    posStep = (double) (currentPosition - lastPosition) / 100 / timeSteps;
                    step = 0;
                    while (lastTime + (step * density) <= lastTime + timeDelta) {
                        final double stepPos = (double) Math.round(((double) lastPosition / 100 + (posStep * step)) * 1000) / 1000;
                        commands.put(lastTime + (step * density), new ArrayList<ButtplugDeviceMessage>(){{
                            add(new SingleMotorVibrateCmd(stepPos));
                        }});
                        step += 1;
                    }
                } else {
                    commands.put(currentTime, new ArrayList<ButtplugDeviceMessage>(){{
                        add(new SingleMotorVibrateCmd((double) currentPosition / 100));
                    }});
                }
            }
            lastTime = currentTime;
            lastPosition = currentPosition;
        }
        // Make sure we stop the vibrator at the end
        if (lastPosition != 0) {
            commands.put(lastTime + density, new ArrayList<ButtplugDeviceMessage>(){{
                add(new SingleMotorVibrateCmd(0));
            }});
        }
        return commands;
    }

    private static SparseArrayCompat<List<ButtplugDeviceMessage>> funscriptToVorzeCommands(List<HapticCommand> hapticCommands, FunscriptProperties properties) {
        int lastTime = 0;
        int lastPosition = 0;

        int currentTime;
        int currentPosition;
        final SparseArrayCompat<List<ButtplugDeviceMessage>> commands = new SparseArrayCompat<>();

        int timeDelta;
        int positionDelta;

        int speed;

        for (HapticCommand hapticCommand : hapticCommands) {
            FunscriptCommand funscriptCommand = (FunscriptCommand) hapticCommand;

            currentTime = funscriptCommand.getTime();
            currentPosition = funscriptCommand.getPosition();

            timeDelta = currentTime - lastTime;
            // If more than a certain amount of time exists between 2 commands, add a command to stop after the last
            if (timeDelta > HapticCommandToButtplugMessage.maxDelta) {
                commands.put(lastTime + HapticCommandToButtplugMessage.maxDelta, new ArrayList<ButtplugDeviceMessage>() {{
                    add(new VorzeA10CycloneCmd(0, true));
                }});
            }

            // We still need to calculate the Launch speed from the commands to set vorze speed.
            positionDelta = Math.abs(currentPosition - lastPosition);
            if (positionDelta != 0) {
                speed = (int) Math.floor(25000 * Math.pow((double) (timeDelta * 90) / positionDelta, -1.05));
                final int clampedSpeed = Math.min(Math.max(speed, 0), 99);
                final boolean clockwise = lastPosition > currentPosition;
                commands.put(lastTime, new ArrayList<ButtplugDeviceMessage>() {{
                    add(new VorzeA10CycloneCmd(clampedSpeed, clockwise));
                }});
            }
            lastTime = funscriptCommand.getTime();
            lastPosition = funscriptCommand.getPosition();
        }
        // Make sure we stop the Vorze at the end
        commands.put(lastTime, new ArrayList<ButtplugDeviceMessage>(){{
            add(new VorzeA10CycloneCmd(0, true));
        }});
        return commands;
    }

    private static SparseArrayCompat<List<ButtplugDeviceMessage>> kiirooToFleshlightLaunchCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;
        int lastSpeed = 0;
        int currentTime;
        int currentSpeed = 0;
        int timeDelta;
        int limitedSpeed = 0;
        final SparseArrayCompat<List<ButtplugDeviceMessage>> commands = new SparseArrayCompat<>();
        for (HapticCommand hapticCommand : hapticCommands) {
            KiirooCommand kiirooCommand = (KiirooCommand) hapticCommand;
            HapticDevice commandDevice = kiirooCommand.getDevice();
            if (commandDevice != HapticDevice.ANY && commandDevice != HapticDevice.LINEAR) {
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
                currentSpeed = (int) Math.floor(100 - (((double) currentSpeed / 100) + ((double) currentSpeed / 100 * .1)));
                if (currentSpeed > lastSpeed) {
                    currentSpeed = (int) Math.floor(lastSpeed + ((double) (currentSpeed - lastSpeed) / 6));
                } else {
                    currentSpeed = (int) Math.floor(lastSpeed - ((double) currentSpeed / 2));
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

    private static SparseArrayCompat<List<ButtplugDeviceMessage>> kiirooToSingleMotorVibrateCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;
        int lastPosition = 0;

        // amount of time (in milliseconds) to put between every interpolated vibration command
        final int density = 75;
        final SparseArrayCompat<List<ButtplugDeviceMessage>> commands = new SparseArrayCompat<>();

        int currentPosition;

        int timeDelta;

        int timeSteps;
        double posStep;
        int step;

        for (HapticCommand hapticCommand : hapticCommands) {
            KiirooCommand kiirooCommand = (KiirooCommand) hapticCommand;
            HapticDevice commandDevice = kiirooCommand.getDevice();
            if (commandDevice != HapticDevice.ANY && commandDevice != HapticDevice.VIBRATE) {
                continue;
            }

            final int currentTime = kiirooCommand.getTime();
            // Convert to 0-100
            if (commandDevice == HapticDevice.ANY) {
                currentPosition = 100 - (kiirooCommand.getPosition() * 25);
            } else {
                currentPosition = (int) Math.floor((double) kiirooCommand.getPosition() / 6 * 100);
            }

            timeDelta = currentTime - lastTime;
            if (timeDelta > 0) {
                // Set a maximum time delta, otherwise we'll have ramps that can last multiple minutes.
                if (timeDelta > HapticCommandToButtplugMessage.maxDelta) {
                    timeDelta = HapticCommandToButtplugMessage.maxDelta - density;
                    commands.put(lastTime + timeDelta, new ArrayList<ButtplugDeviceMessage>(){{
                        add(new SingleMotorVibrateCmd(0));
                    }});
                }

                timeSteps = (int) Math.floor((double) timeDelta / density);
                if (timeSteps > 0) {
                    posStep = (double) (currentPosition - lastPosition) / 100 / timeSteps;
                    step = 0;
                    while (lastTime + (step * density) <= lastTime + timeDelta) {
                        final double stepPos = (double) lastPosition / 100 + (posStep * step);
                        commands.put(lastTime + (step * density), new ArrayList<ButtplugDeviceMessage>(){{
                            add(new SingleMotorVibrateCmd(stepPos));
                        }});
                        step += 1;
                    }
                } else {
                    final double newPosition = (double) currentPosition / 100;
                    commands.put(currentTime, new ArrayList<ButtplugDeviceMessage>(){{
                        add(new SingleMotorVibrateCmd(newPosition));
                    }});
                }
            }
            lastTime = currentTime;
            lastPosition = currentPosition;
        }
        // Make sure we stop the vibrator at the end
        if (lastPosition != 0) {
            commands.put(lastTime + density, new ArrayList<ButtplugDeviceMessage>(){{
                add(new SingleMotorVibrateCmd(0));
            }});
        }
        return commands;
    }

    private static SparseArrayCompat<List<ButtplugDeviceMessage>> vorzeToVorzeCommands(List<HapticCommand> hapticCommands) {
        int lastTime = 0;

        int currentTime;

        final SparseArrayCompat<List<ButtplugDeviceMessage>> commands = new SparseArrayCompat<>();

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
