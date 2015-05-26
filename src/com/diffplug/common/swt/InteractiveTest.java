/**
 * Copyright 2015 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.common.swt;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;

import com.diffplug.common.base.Box;
import com.diffplug.common.base.Errors;
import com.diffplug.common.base.Unhandled;

/**
 * InteractiveTest opens a Coat or a Shell, and displays instructions for a human
 * tester to determine whether the test passed or failed.  This makes it extremely
 * easy to create and specify a UI test, which can be converted into an automated
 * UI test at a later date.
 * 
 * If the system property 'com.diffplug.InteractiveTest.autoclose.milliseconds'
 * is set, then the tests will open and then automatically pass after the
 * specified timeout.
 * 
 * This lets a headless environment keep the tests in working order, although a
 * human is required for full validation.
 */
public class InteractiveTest {
	private InteractiveTest() {}

	/** Returns true iff the test should be run in "autoclose" mode. */
	private static Optional<Integer> autoCloseMs() {
		String value = System.getProperty(AUTOCLOSE_KEY);
		if (value == null) {
			return Optional.empty();
		} else {
			return Errors.log().getWithDefault(
					() -> Optional.of(Integer.parseInt(value)),
					Optional.<Integer> empty());
		}
	}

	public static final String AUTOCLOSE_KEY = "com.diffplug.test.autoclose.milliseconds";

	public static final int CMP_COLS = 60;
	public static final int CMP_ROWS = 40;

	/**
	 * @param instructions Instructions for the user to follow.
	 * @param coat A function to populate the test composite.
	 */
	public static void testCoat(String instructions, Coat coat) {
		testCoat(instructions, CMP_COLS, CMP_ROWS, coat);
	}

	/**
	 * @param instructions Instructions for the user to follow.
	 * @param cols Width of the test composite (unit is the system font height).
	 * @param rows Height of the test composite (unit is the system font height). 
	 * @param coat A function to populate the test composite.
	 */
	public static void testCoat(String instructions, int cols, int rows, Coat coat) {
		Point size = null;
		if (cols > 0 || rows > 0) {
			size = SwtMisc.scaleByFontHeight(cols, rows);
		}
		testCoat(instructions, size, coat);
	}

	/**
	 * @param instructions Instructions for the user to follow.
	 * @param size Width and height of the test composite (unit is the system font height).
	 * @param coat A function to populate the test composite.
	 */
	public static void testCoat(String instructions, Point size, Coat coat) {
		testShell(instructions, display -> {
			return Shells.create(SWT.SHELL_TRIM, coat)
					.setTitle("UnderTest")
					.setSize(size)
					.openOnDisplay();
		});
	}

	/**
	 * @param instructions Instructions for the user to follow.
	 * @param function A function which takes a Display and returns a Shell to test.  The instructions will pop-up next to the test shell.
	 */
	public static void testShell(String instructions, Function<Display, Shell> function) {
		Display display = Display.getDefault();

		try {
			// create the shell under test
			Shell underTest = function.apply(display);
			underTest.setLocation(10, 10);

			// create the test dialog
			Box.NonNull<TestResult> result = Box.NonNull.of(TestResult.FAIL);
			Shell instructionsDialog = openInstructions(underTest, instructions, result);
			underTest.setActive();

			// when either is disposed, dispose the other
			disposeIfDisposed(underTest, instructionsDialog);
			disposeIfDisposed(instructionsDialog, underTest);

			// if we're in autoclose mode, then we'll dispose the test after a timeout
			autoCloseMs().ifPresent(autoCloseMs -> {
				SwtExec.async().guardOn(underTest).timerExec(autoCloseMs, () -> {
					// dispose the shells
					instructionsDialog.dispose();
					underTest.dispose();
					// set the result to be a pass
					result.set(TestResult.PASS);
				});
			});

			// wait for the result
			SwtMisc.loopUntilDisposed(underTest);

			// take the appropriate action for that result
			switch (result.get()) {
			case PASS:
				return;
			case FAIL:
				throw new AssertionError(instructions);
			default:
				throw Unhandled.enumException(result.get());
			}
		} finally {
			// dispose everything at the end
			for (Shell shell : display.getShells()) {
				shell.dispose();
			}
		}
	}

	/** Cascades disposal. */
	private static void disposeIfDisposed(Widget ifDisposed, Widget thenDispose) {
		ifDisposed.addListener(SWT.Dispose, e -> {
			if (!thenDispose.isDisposed()) {
				thenDispose.dispose();
			}
		});
	}

	/**
	 * 
	 * Same as testShell, but for situations where it is impossible to return
	 * the shell handle, so we get the shell automatically.
	 * 
	 * @param instructions Instructions for the user to follow.
	 * @param function A function which takes a Display and returns a Shell to test.  The instructions will pop-up next to the test shell.
	 */
	public static void testShellWithoutHandle(String instructions, Consumer<Display> consumer) {
		testShell(instructions, display -> {
			// initiate the thing that should create the dialog
			consumer.accept(display);

			// wait until this dialog is created
			SwtMisc.loopUntil(() -> display.getActiveShell() != null);

			// return the dialog that was created
			return display.getActiveShell();
		});
	}

	/** The result of a TestDialog. */
	private enum TestResult {
		PASS, FAIL
	}

	/** Opens the instructions dialog. */
	private static Shell openInstructions(Shell underTest, String instructions, Box.NonNull<TestResult> result) {
		Shell instructionsShell = Shells.create(SWT.TITLE | SWT.BORDER, cmp -> {
			Layouts.setGrid(cmp).numColumns(3);

			// show the instructions
			Text text = new Text(cmp, SWT.WRAP);
			Layouts.setGridData(text).horizontalSpan(3).grabAll();
			text.setEditable(false);
			text.setText(instructions);

			// pass / fail buttons
			Layouts.newGridPlaceholder(cmp).grabHorizontal();

			Consumer<TestResult> buttonCreator = val -> {
				Button btn = new Button(cmp, SWT.PUSH);
				btn.setText(val.name());
				btn.addListener(SWT.Selection, e -> {
					result.set(val);
					cmp.getShell().dispose();
				});
				Layouts.setGridData(btn).widthHint(SwtMisc.defaultButtonWidth());
			};
			buttonCreator.accept(TestResult.PASS);
			buttonCreator.accept(TestResult.FAIL);
		})
				.setTitle("PASS / FAIL")
				.setSize(SwtMisc.scaleByFontHeight(18, 0))
				.openOn(underTest);

		// put the instructions to the right of the dialog under test 
		Rectangle instructionsBounds = instructionsShell.getBounds();
		Rectangle underTestBounds = underTest.getBounds();
		instructionsBounds.x = underTestBounds.x + underTestBounds.width + HORIZONTAL_SEP;
		instructionsBounds.y = underTestBounds.y;
		instructionsShell.setBounds(instructionsBounds);

		// return the value
		return instructionsShell;
	}

	private static final int HORIZONTAL_SEP = 15;
}
