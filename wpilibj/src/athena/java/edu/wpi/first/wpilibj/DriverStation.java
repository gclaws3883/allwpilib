/*----------------------------------------------------------------------------*/
/* Copyright (c) FIRST 2008-2016. All Rights Reserved.                        */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

package edu.wpi.first.wpilibj;

import java.nio.ByteBuffer;

import edu.wpi.first.wpilibj.communication.FRCNetworkCommunicationsLibrary;
import edu.wpi.first.wpilibj.communication.HALAllianceStationID;
import edu.wpi.first.wpilibj.communication.HALControlWord;
import edu.wpi.first.wpilibj.hal.HALUtil;
import edu.wpi.first.wpilibj.hal.PowerJNI;

/**
 * Provide access to the network communication data to / from the Driver Station.
 */
public class DriverStation implements RobotState.Interface {

  /**
   * Number of Joystick Ports.
   */
  public static final int kJoystickPorts = 6;

  private class HALJoystickButtons {
    public int m_buttons;
    public byte m_count;
  }

  private class HALJoystickAxes {
    public short[] m_axes;
    public byte m_count;

    public HALJoystickAxes(int count) {
      m_axes = new short[count];
    }
  }

  private class HALJoystickPOVs {
    public short[] m_povs;
    public byte m_count;

    public HALJoystickPOVs(int count) {
      m_povs = new short[count];
    }
  }

  /**
   * The robot alliance that the robot is a part of.
   */
  public enum Alliance {
    Red, Blue, Invalid
  }

  private static final double JOYSTICK_UNPLUGGED_MESSAGE_INTERVAL = 1.0;
  private double m_nextMessageTime = 0.0;

  private static class DriverStationTask implements Runnable {

    private DriverStation m_ds;

    DriverStationTask(DriverStation ds) {
      m_ds = ds;
    }

    public void run() {
      m_ds.task();
    }
  } /* DriverStationTask */

  private static DriverStation instance = new DriverStation();

  private HALJoystickAxes[] m_joystickAxes = new HALJoystickAxes[kJoystickPorts];
  private HALJoystickPOVs[] m_joystickPOVs = new HALJoystickPOVs[kJoystickPorts];
  private HALJoystickButtons[] m_joystickButtons = new HALJoystickButtons[kJoystickPorts];

  private HALJoystickAxes[] m_joystickAxesCache = new HALJoystickAxes[kJoystickPorts];
  private HALJoystickPOVs[] m_joystickPOVsCache = new HALJoystickPOVs[kJoystickPorts];
  private HALJoystickButtons[] m_joystickButtonsCache = new HALJoystickButtons[kJoystickPorts];
  // preallocated byte buffer for button count
  private ByteBuffer m_buttonCountBuffer = ByteBuffer.allocateDirect(1); 

  private int[] m_joystickIsXbox = new int[kJoystickPorts];
  private int[] m_joystickType = new int[kJoystickPorts];
  private String[] m_joystickName = new String[kJoystickPorts];
  private int[][] m_joystickAxisType =
      new int[kJoystickPorts][FRCNetworkCommunicationsLibrary.kMaxJoystickAxes];

  private Thread m_thread;
  private final Object m_dataSem;
  private final Object m_newControlDataMutex;
  private final Object m_joystickMutex;
  private volatile boolean m_threadKeepAlive = true;
  
  private boolean m_userInDisabled = false;
  private boolean m_userInAutonomous = false;
  private boolean m_userInTeleop = false;
  private boolean m_userInTest = false;
  private boolean m_newControlData;
  private final long m_packetDataAvailableMutex;
  private final long m_packetDataAvailableSem;

  /**
   * Gets an instance of the DriverStation
   *
   * @return The DriverStation.
   */
  public static DriverStation getInstance() {
    return DriverStation.instance;
  }

  /**
   * DriverStation constructor.
   *
   * <p>The single DriverStation instance is created statically with the instance static member
   * variable.
   */
  protected DriverStation() {
    m_dataSem = new Object();
    m_joystickMutex = new Object();
    m_newControlDataMutex = new Object();
    for (int i = 0; i < kJoystickPorts; i++) {
      m_joystickButtons[i] = new HALJoystickButtons();
      m_joystickAxes[i] = new HALJoystickAxes(FRCNetworkCommunicationsLibrary.kMaxJoystickAxes);
      m_joystickPOVs[i] = new HALJoystickPOVs(FRCNetworkCommunicationsLibrary.kMaxJoystickPOVs);

      m_joystickButtonsCache[i] = new HALJoystickButtons();
      m_joystickAxesCache[i] = 
          new HALJoystickAxes(FRCNetworkCommunicationsLibrary.kMaxJoystickAxes);
      m_joystickPOVsCache[i] = 
          new HALJoystickPOVs(FRCNetworkCommunicationsLibrary.kMaxJoystickPOVs);
    }

    m_packetDataAvailableMutex = HALUtil.initializeMutexNormal();
    m_packetDataAvailableSem = HALUtil.initializeMultiWait();
    FRCNetworkCommunicationsLibrary.setNewDataSem(m_packetDataAvailableSem);

    m_thread = new Thread(new DriverStationTask(this), "FRCDriverStation");
    m_thread.setPriority((Thread.NORM_PRIORITY + Thread.MAX_PRIORITY) / 2);

    m_thread.start();
  }

  /**
   * Kill the thread.
   */
  public void release() {
    m_threadKeepAlive = false;
  }

  /**
   * Provides the service routine for the DS polling m_thread.
   */
  private void task() {
    int safetyCounter = 0;
    while (m_threadKeepAlive) {
      HALUtil.takeMultiWait(m_packetDataAvailableSem, m_packetDataAvailableMutex);
      getData();
      synchronized (m_dataSem) {
        m_dataSem.notifyAll();
      }
      if (++safetyCounter >= 4) {
        MotorSafetyHelper.checkMotors();
        safetyCounter = 0;
      }
      if (m_userInDisabled) {
        FRCNetworkCommunicationsLibrary.FRCNetworkCommunicationObserveUserProgramDisabled();
      }
      if (m_userInAutonomous) {
        FRCNetworkCommunicationsLibrary.FRCNetworkCommunicationObserveUserProgramAutonomous();
      }
      if (m_userInTeleop) {
        FRCNetworkCommunicationsLibrary.FRCNetworkCommunicationObserveUserProgramTeleop();
      }
      if (m_userInTest) {
        FRCNetworkCommunicationsLibrary.FRCNetworkCommunicationObserveUserProgramTest();
      }
    }
  }

  /**
   * Wait for new data from the driver station.
   */
  public void waitForData() {
    waitForData(0);
  }

  /**
   * Wait for new data or for timeout, which ever comes first. If timeout is 0, wait for new data
   * only.
   *
   * @param timeout The maximum time in milliseconds to wait.
   */
  public void waitForData(long timeout) {
    synchronized (m_dataSem) {
      try {
        m_dataSem.wait(timeout);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Copy data from the DS task for the user. If no new data exists, it will just be returned,
   * otherwise the data will be copied from the DS polling loop.
   */
  protected void getData() {
    // Get the status of all of the joysticks
    for (byte stick = 0; stick < kJoystickPorts; stick++) {
      m_joystickAxesCache[stick].m_count = 
          FRCNetworkCommunicationsLibrary.HALGetJoystickAxes(stick, 
                                                             m_joystickAxesCache[stick].m_axes);
      m_joystickPOVsCache[stick].m_count = 
          FRCNetworkCommunicationsLibrary.HALGetJoystickPOVs(stick, 
                                                             m_joystickPOVsCache[stick].m_povs);
      m_joystickButtonsCache[stick].m_buttons =
          FRCNetworkCommunicationsLibrary.HALGetJoystickButtons(stick, m_buttonCountBuffer);
      m_joystickButtonsCache[stick].m_count = m_buttonCountBuffer.get(0);
    }
    // lock joystick mutex to swap cache data
    synchronized (m_joystickMutex) {
      // move cache to actual data
      HALJoystickAxes[] currentAxes = m_joystickAxes;
      m_joystickAxes = m_joystickAxesCache;
      m_joystickAxesCache = currentAxes;

      HALJoystickButtons[] currentButtons = m_joystickButtons;
      m_joystickButtons = m_joystickButtonsCache;
      m_joystickButtonsCache = currentButtons;

      HALJoystickPOVs[] currentPOVs = m_joystickPOVs;
      m_joystickPOVs = m_joystickPOVsCache;
      m_joystickPOVsCache = currentPOVs;
    }
    //Lock new control data mutex and set new control data.
    synchronized (m_newControlDataMutex) {
      m_newControlData = true;
    }
  }

  /**
   * Read the battery voltage.
   *
   * @return The battery voltage in Volts.
   */
  public double getBatteryVoltage() {
    return PowerJNI.getVinVoltage();
  }

  /**
   * Reports errors related to unplugged joysticks Throttles the errors so that they don't overwhelm
   * the DS.
   */
  private void reportJoystickUnpluggedError(String message) {
    double currentTime = Timer.getFPGATimestamp();
    if (currentTime > m_nextMessageTime) {
      reportError(message, false);
      m_nextMessageTime = currentTime + JOYSTICK_UNPLUGGED_MESSAGE_INTERVAL;
    }
  }

  /**
   * Reports errors related to unplugged joysticks Throttles the errors so that they don't overwhelm
   * the DS.
   */
  private void reportJoystickUnpluggedWarning(String message) {
    double currentTime = Timer.getFPGATimestamp();
    if (currentTime > m_nextMessageTime) {
      reportWarning(message, false);
      m_nextMessageTime = currentTime + JOYSTICK_UNPLUGGED_MESSAGE_INTERVAL;
    }
  }

  /**
   * Get the value of the axis on a joystick. This depends on the mapping of the joystick connected
   * to the specified port.
   *
   * @param stick The joystick to read.
   * @param axis  The analog axis value to read from the joystick.
   * @return The value of the axis on the joystick.
   */
  public double getStickAxis(int stick, int axis) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-5");
    }
    if (axis < 0 || axis >= FRCNetworkCommunicationsLibrary.kMaxJoystickAxes) {
      throw new RuntimeException("Joystick axis is out of range");
    }
    
    boolean error = false;
    double retVal = 0.0;
    synchronized (m_joystickMutex) {
      if (axis >= m_joystickAxes[stick].m_count) {
        // set error
        error = true;
        retVal = 0.0;
      } else {
        retVal =  m_joystickAxes[stick].m_axes[axis];
      }
    } 
    if (error) {
      reportJoystickUnpluggedWarning("Joystick axis " + axis + " on port " + stick
          + " not available, check if controller is plugged in");
    }
    return retVal;
  }

  /**
   * Returns the number of axes on a given joystick port.
   *
   * @param stick The joystick port number
   * @return The number of axes on the indicated joystick
   */
  public int getStickAxisCount(int stick) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-5");
    }
    synchronized (m_joystickMutex) {
      return m_joystickAxes[stick].m_count;
    }
  }

  /**
   * Get the state of a POV on the joystick.
   *
   * @return the angle of the POV in degrees, or -1 if the POV is not pressed.
   */
  public int getStickPOV(int stick, int pov) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-5");
    }
    if (pov < 0 || pov >= FRCNetworkCommunicationsLibrary.kMaxJoystickPOVs) {
      throw new RuntimeException("Joystick POV is out of range");
    }
    boolean error = false;
    int retVal = -1;
    synchronized (m_joystickMutex) {
      if (pov >= m_joystickPOVs[stick].m_count) {
        error = true;
        retVal = -1;
      } else {
        retVal = m_joystickPOVs[stick].m_povs[pov];
      }
    }
    if (error) {
      reportJoystickUnpluggedWarning("Joystick POV " + pov + " on port " + stick
          + " not available, check if controller is plugged in");
    }
    return retVal;
  }

  /**
   * Returns the number of POVs on a given joystick port.
   *
   * @param stick The joystick port number
   * @return The number of POVs on the indicated joystick
   */
  public int getStickPOVCount(int stick) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-5");
    }
    synchronized (m_joystickMutex) {
      return m_joystickPOVs[stick].m_count;
    }
  }

  /**
   * The state of the buttons on the joystick.
   *
   * @param stick The joystick to read.
   * @return The state of the buttons on the joystick.
   */
  public int getStickButtons(final int stick) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-3");
    }
    synchronized (m_joystickMutex) {
      return m_joystickButtons[stick].m_buttons;
    }
  }

  /**
   * The state of one joystick button. Button indexes begin at 1.
   *
   * @param stick  The joystick to read.
   * @param button The button index, beginning at 1.
   * @return The state of the joystick button.
   */
  public boolean getStickButton(final int stick, byte button) {
    if (button <= 0) {
      reportJoystickUnpluggedError("Button indexes begin at 1 in WPILib for C++ and Java\n");
      return false;
    }
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-3");
    }
    boolean error = false;
    boolean retVal = false;
    synchronized (m_joystickMutex) {
      if (button > m_joystickButtons[stick].m_count) {
        error = true;
        retVal = false;
      } else {
        retVal = ((0x1 << (button - 1)) & m_joystickButtons[stick].m_buttons) != 0;
      }
    }
    if (error) {
      reportJoystickUnpluggedWarning("Joystick Button " + button + " on port " + stick
          + " not available, check if controller is plugged in");
    }
    return retVal;
  }

  /**
   * Gets the number of buttons on a joystick.
   *
   * @param stick The joystick port number
   * @return The number of buttons on the indicated joystick
   */
  public int getStickButtonCount(int stick) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-5");
    }
    synchronized (m_joystickMutex) {
      return m_joystickButtons[stick].m_count;
    }
  }

  /**
   * Gets the value of isXbox on a joystick.
   *
   * @param stick The joystick port number
   * @return A boolean that returns the value of isXbox
   */
  public boolean getJoystickIsXbox(int stick) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-5");
    }
    boolean error = false;
    boolean retVal = false;
    synchronized (m_joystickMutex) {
      // TODO: Remove this when calling for descriptor on empty stick no longer
      // crashes
      if (1 > m_joystickButtons[stick].m_count && 1 > m_joystickAxes[stick].m_count) {
        error = true;
        retVal = false;
      } else if (FRCNetworkCommunicationsLibrary.HALGetJoystickIsXbox((byte) stick) == 1) {
        retVal = true;
      }
    }
    if (error) {
      reportJoystickUnpluggedWarning("Joystick on port " + stick
          + " not available, check if controller is plugged in");
    }
    return retVal;
  }

  /**
   * Gets the value of type on a joystick.
   *
   * @param stick The joystick port number
   * @return The value of type
   */
  public int getJoystickType(int stick) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-5");
    }
    boolean error = false;
    int retVal = -1;
    synchronized (m_joystickMutex) {
      // TODO: Remove this when calling for descriptor on empty stick no longer
      // crashes
      if (1 > m_joystickButtons[stick].m_count && 1 > m_joystickAxes[stick].m_count) {
        error = true;
        retVal = -1;
      } else {
        retVal = FRCNetworkCommunicationsLibrary.HALGetJoystickType((byte) stick);
      }
    }
    if (error) {
      reportJoystickUnpluggedWarning("Joystick on port " + stick
          + " not available, check if controller is plugged in");
    }
    return retVal;
  }

  /**
   * Gets the name of the joystick at a port.
   *
   * @param stick The joystick port number
   * @return The value of name
   */
  public String getJoystickName(int stick) {
    if (stick < 0 || stick >= kJoystickPorts) {
      throw new RuntimeException("Joystick index is out of range, should be 0-5");
    }
    boolean error = false;
    String retVal = "";  
    synchronized (m_joystickMutex) {
      // TODO: Remove this when calling for descriptor on empty stick no longer
      // crashes
      if (1 > m_joystickButtons[stick].m_count && 1 > m_joystickAxes[stick].m_count) {
        error = true;
        retVal = "";
      } else {
        retVal = FRCNetworkCommunicationsLibrary.HALGetJoystickName((byte) stick);
      }
    } 
    if (error) {
      reportJoystickUnpluggedWarning("Joystick on port " + stick
          + " not available, check if controller is plugged in");
    }
    return retVal;
  }

  /**
   * Gets a value indicating whether the Driver Station requires the robot to be enabled.
   *
   * @return True if the robot is enabled, false otherwise.
   */
  public boolean isEnabled() {
    HALControlWord controlWord = FRCNetworkCommunicationsLibrary.HALGetControlWord();
    return controlWord.getEnabled() && controlWord.getDSAttached();
  }

  /**
   * Gets a value indicating whether the Driver Station requires the robot to be disabled.
   *
   * @return True if the robot should be disabled, false otherwise.
   */
  public boolean isDisabled() {
    return !isEnabled();
  }

  /**
   * Gets a value indicating whether the Driver Station requires the robot to be running in
   * autonomous mode.
   *
   * @return True if autonomous mode should be enabled, false otherwise.
   */
  public boolean isAutonomous() {
    HALControlWord controlWord = FRCNetworkCommunicationsLibrary.HALGetControlWord();
    return controlWord.getAutonomous();
  }

  /**
   * Gets a value indicating whether the Driver Station requires the robot to be running in test
   * mode.
   *
   * @return True if test mode should be enabled, false otherwise.
   */
  public boolean isTest() {
    HALControlWord controlWord = FRCNetworkCommunicationsLibrary.HALGetControlWord();
    return controlWord.getTest();
  }

  /**
   * Gets a value indicating whether the Driver Station requires the robot to be running in
   * operator-controlled mode.
   *
   * @return True if operator-controlled mode should be enabled, false otherwise.
   */
  public boolean isOperatorControl() {
    return !(isAutonomous() || isTest());
  }

  /**
   * Gets a value indicating whether the FPGA outputs are enabled. The outputs may be disabled if
   * the robot is disabled or e-stopped, the watdhog has expired, or if the roboRIO browns out.
   *
   * @return True if the FPGA outputs are enabled.
   */
  public boolean isSysActive() {
    return FRCNetworkCommunicationsLibrary.HALGetSystemActive();
  }

  /**
   * Check if the system is browned out.
   *
   * @return True if the system is browned out
   */
  public boolean isBrownedOut() {
    return FRCNetworkCommunicationsLibrary.HALGetBrownedOut();
  }

  /**
   * Has a new control packet from the driver station arrived since the last time this function was
   * called?
   *
   * @return True if the control data has been updated since the last call.
   */
  public boolean isNewControlData() {
    synchronized (m_newControlDataMutex) {
      boolean result = m_newControlData;
      m_newControlData = false;
      return result;
    }
  }

  /**
   * Get the current alliance from the FMS.
   *
   * @return the current alliance
   */
  public Alliance getAlliance() {
    HALAllianceStationID allianceStationID =
        FRCNetworkCommunicationsLibrary.HALGetAllianceStation();
    if (allianceStationID == null) {
      return Alliance.Invalid;
    }

    switch (allianceStationID) {
      case Red1:
      case Red2:
      case Red3:
        return Alliance.Red;

      case Blue1:
      case Blue2:
      case Blue3:
        return Alliance.Blue;

      default:
        return Alliance.Invalid;
    }
  }

  /**
   * Gets the location of the team's driver station controls.
   *
   * @return the location of the team's driver station controls: 1, 2, or 3
   */
  public int getLocation() {
    HALAllianceStationID allianceStationID =
        FRCNetworkCommunicationsLibrary.HALGetAllianceStation();
    if (allianceStationID == null) {
      return 0;
    }
    switch (allianceStationID) {
      case Red1:
      case Blue1:
        return 1;

      case Red2:
      case Blue2:
        return 2;

      case Blue3:
      case Red3:
        return 3;

      default:
        return 0;
    }
  }

  /**
   * Is the driver station attached to a Field Management System? Note: This does not work with the
   * Blue DS.
   *
   * @return True if the robot is competing on a field being controlled by a Field Management System
   */
  public boolean isFMSAttached() {
    HALControlWord controlWord = FRCNetworkCommunicationsLibrary.HALGetControlWord();
    return controlWord.getFMSAttached();
  }

  public boolean isDSAttached() {
    HALControlWord controlWord = FRCNetworkCommunicationsLibrary.HALGetControlWord();
    return controlWord.getDSAttached();
  }

  /**
   * Return the approximate match time The FMS does not send an official match time to the robots,
   * but does send an approximate match time. The value will count down the time remaining in the
   * current period (auto or teleop). Warning: This is not an official time (so it cannot be used to
   * dispute ref calls or guarantee that a function will trigger before the match ends) The
   * Practice Match function of the DS approximates the behaviour seen on the field.
   *
   * @return Time remaining in current match period (auto or teleop) in seconds
   */
  public double getMatchTime() {
    return FRCNetworkCommunicationsLibrary.HALGetMatchTime();
  }

  /**
   * Report error to Driver Station. Also prints error to System.err Optionally appends Stack trace
   * to error message.
   *
   * @param printTrace If true, append stack trace to error string
   */
  public static void reportError(String error, boolean printTrace) {
    reportErrorImpl(true, 1, error, printTrace);
  }

  /**
   * Report warning to Driver Station. Also prints error to System.err Optionally appends Stack
   * trace to warning message.
   *
   * @param printTrace If true, append stack trace to warning string
   */
  public static void reportWarning(String error, boolean printTrace) {
    reportErrorImpl(false, 1, error, printTrace);
  }

  private static void reportErrorImpl(boolean isError, int code, String error, boolean
      printTrace) {
    StackTraceElement[] traces = Thread.currentThread().getStackTrace();
    String locString;
    if (traces.length > 3) {
      locString = traces[3].toString();
    } else {
      locString = new String();
    }
    boolean haveLoc = false;
    String traceString = " at ";
    for (int i = 3; i < traces.length; i++) {
      String loc = traces[i].toString();
      traceString += loc + "\n";
      // get first user function
      if (!haveLoc && !loc.startsWith("edu.wpi.first.wpilibj")) {
        locString = loc;
        haveLoc = true;
      }
    }
    FRCNetworkCommunicationsLibrary.HALSendError(isError, code, false, error, locString,
        printTrace ? traceString : "", true);
  }

  /**
   * Only to be used to tell the Driver Station what code you claim to be executing for diagnostic
   * purposes only.
   *
   * @param entering If true, starting disabled code; if false, leaving disabled code
   */
  @SuppressWarnings("MethodName")
  public void InDisabled(boolean entering) {
    m_userInDisabled = entering;
  }

  /**
   * Only to be used to tell the Driver Station what code you claim to be executing for diagnostic
   * purposes only.
   *
   * @param entering If true, starting autonomous code; if false, leaving autonomous code
   */
  @SuppressWarnings("MethodName")
  public void InAutonomous(boolean entering) {
    m_userInAutonomous = entering;
  }

  /**
   * Only to be used to tell the Driver Station what code you claim to be executing for diagnostic
   * purposes only.
   *
   * @param entering If true, starting teleop code; if false, leaving teleop code
   */
  @SuppressWarnings("MethodName")
  public void InOperatorControl(boolean entering) {
    m_userInTeleop = entering;
  }

  /**
   * Only to be used to tell the Driver Station what code you claim to be executing for diagnostic
   * purposes only.
   *
   * @param entering If true, starting test code; if false, leaving test code
   */
  @SuppressWarnings("MethodName")
  public void InTest(boolean entering) {
    m_userInTest = entering;
  }
}
