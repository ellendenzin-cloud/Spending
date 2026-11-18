# SpendWatch

A $185/month budget tracker for Android that logs your charges by itself, shows what's left
in a home screen widget, and carries overspending into the next month so a bad month
actually costs you something.

Nothing leaves the phone. The app declares no internet permission at all.

## Your categories

| Category | Monthly |
|---|---|
| Groceries & household | $100 |
| Eating out | $30 |
| Laundry | $20 |
| Gas & parking | $15 |
| Spotify / fun | $10 |
| Clothes, Amazon, extras | $10 |
| **Total** | **$185** |

All six are editable in the app — tap a category to change its budget or its keywords.

## How the auto-logging works

Android lets an app with notification access read the notifications other apps post. When
your bank or card app says "You spent $42.17 at TARGET", SpendWatch pulls out the amount
and the merchant, matches the merchant to a category, and logs it.

It is deliberately cautious. A notification has to look like a completed purchase, so
declines, balance alerts, deposits, statements, verification codes, rewards offers and
ordinary app notifications are all ignored. Refunds are recognized and give the money
back. A charge that arrives twice — once from the bank, once from the card app — counts
once.

A merchant it doesn't recognize goes to a **Needs a category** list at the top of the
screen. Tap the right category once and it remembers that merchant from then on.

**This only works if your bank sends push notifications per charge.** Most do by default;
if yours doesn't, turn on transaction alerts in your bank's app. Manual entry is always
there as a fallback.

## The scary part

- The big number at the top is what's left for the whole month, and it turns red and goes
  negative when you're over.
- Under it: what you can spend per day for the rest of the month. It gets uncomfortable on
  its own.
- Each category has a bar that runs green → amber → orange → red. Past 100% the row says
  **over by $X**.
- A permanent silent line in your notification shade shows the running total.
- Cross 70% of a category and you get a quiet notification. Blow a category and you get a
  loud one that vibrates, naming the amount and reminding you it's coming out of next
  month.
- A blown category tells you, right on the card, what next month's allowance drops to.

## Rollover

Overspend by $18.50 on Eating out in March and April's Eating out starts at $11.50 instead
of $30. Keep overspending and the debt compounds. Behave for one month and the debt clears.

Unspent money does **not** roll forward by default — a good month doesn't bank you credit.
There's a switch in Settings if you want it to.

## Building the APK

The repo includes `.github/workflows/build.yml`, so GitHub builds it for you. Push this to a
repo, open the **Actions** tab, and the run starts on its own (or press **Run workflow**).
When it goes green, open the run, scroll to **Artifacts**, and download **spendwatch-apk**.
Unzip it and tap `app-debug.apk` to install.

The workflow locates the project folder itself, so it works whether these files sit at the
repo root or inside a subfolder.

On a computer: open the folder in Android Studio and run it.

## First run

1. Open SpendWatch. Allow notifications when asked.
2. Tap **Grant notification access** and switch SpendWatch on in the list. This is the
   permission that lets it see your bank's alerts.
3. Long-press the home screen → Widgets → SpendWatch, and drop the widget somewhere you'll
   see it.
4. Make a purchase, or add one by hand to check it looks right.

## Notes

- `minSdk 26`, `targetSdk 35`, Kotlin, plain views, no third-party libraries.
- Money is whole cents in `Long` everywhere; no floating point touches an amount.
- State lives in one `SharedPreferences` JSON blob. 18 months of history is kept, then
  trimmed.
- Month rollover is lazy: the first read in a new month settles every month that passed,
  so the app works fine after you ignore it for a while.
- Widget background tinting of the bars uses `setColorStateList` (Android 12+). Your Pixel
  is well past that.
- The parser and the rollover arithmetic were checked against sample bank notifications and
  a set of overspend scenarios before packaging.
