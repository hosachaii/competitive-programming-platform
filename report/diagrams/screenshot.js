// Captures the UI screenshots used in the report.
// Prereq: the stack is running and prep_demo_data.sh has written demo_data.json.
// Usage: cd report/diagrams && node screenshot.js
//
// Container pages are captured at 1100px (the app's max content width) and the
// narrow auth pages tighter, both at deviceScaleFactor 2 for sharp text; each image
// is then cropped to its actual content by crop_screenshots (run separately).
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const data = JSON.parse(fs.readFileSync(path.join(__dirname, 'demo_data.json'), 'utf8'));
const BASE = data.base;
const OUT = path.join(__dirname, '..', 'images');
// Narrow viewports on purpose: the app content is capped at 1100px, which renders
// below footnote size once fit to an A4 page. ~820px keeps UI text readable at
// (near) full text width. deviceScaleFactor 2 keeps it sharp.
const WIDE = { width: 760, height: 800 };
const NARROW = { width: 620, height: 760 };

async function main() {
  const browser = await chromium.launch();

  async function ctxFor(auth) {
    const ctx = await browser.newContext({
      viewport: WIDE,
      deviceScaleFactor: 2,
      permissions: ['clipboard-read', 'clipboard-write'],
    });
    if (auth) {
      await ctx.addInitScript((a) => {
        localStorage.setItem('accessToken', a.token);
        localStorage.setItem('refreshToken', a.refresh);
        localStorage.setItem('user', JSON.stringify(a.user));
      }, auth);
    }
    return ctx;
  }

  async function shot(ctx, route, file, { vp = WIDE, prep = null, waitFor = null, clipAbove = null } = {}) {
    const page = await ctx.newPage();
    await page.setViewportSize(vp);
    await page.goto(BASE + route, { waitUntil: 'networkidle' });
    if (waitFor) { try { await page.waitForSelector(waitFor, { timeout: 15000 }); } catch (e) {} }
    if (prep) await prep(page);
    await page.waitForTimeout(800);
    let opts = { path: path.join(OUT, file), fullPage: true };
    if (clipAbove) {
      // crop a tall form page to the top, just above the given marker text, so it
      // stays readable and fits on one page
      const box = await page.getByText(clipAbove, { exact: true }).boundingBox().catch(() => null);
      if (box) opts = { path: path.join(OUT, file), clip: { x: 0, y: 0, width: vp.width, height: Math.ceil(box.y - 12) } };
    }
    await page.screenshot(opts);
    console.log('saved', file);
    await page.close();
  }

  const anon = await ctxFor(null);
  const alice = await ctxFor(data.alice);
  const admin = await ctxFor(data.admin);

  // Logged-out auth pages (narrow viewport so the 400px form fills the width)
  await shot(anon, '/login', 'ui_login.png', { vp: NARROW, waitFor: 'form, input' });
  await shot(anon, '/register', 'ui_register.png', { vp: NARROW, waitFor: 'form, input' });

  // User pages
  await shot(alice, '/', 'ui_problem_list.png', { waitFor: 'table, a' });
  await shot(alice, '/leaderboard', 'ui_leaderboard.png', { waitFor: 'table' });

  await shot(alice, '/problems/' + data.problemSlug, 'ui_problem_detail.png', {
    waitFor: '.monaco-editor',
    prep: async (page) => {
      await page.waitForTimeout(1200);
      const editor = page.locator('.monaco-editor').first();
      await editor.click();
      await page.keyboard.press('Control+A');
      await page.keyboard.press('Delete');
      await page.evaluate((code) => navigator.clipboard.writeText(code), data.aceCode);
      await page.keyboard.press('Control+V');
      await page.waitForTimeout(600);
      await page.mouse.wheel(0, -3000);
      await page.waitForTimeout(300);
    },
  });

  // Submission pages (terminal verdicts already stored)
  await shot(alice, '/submissions/' + data.acId, 'ui_submission_accepted.png', { waitFor: '.badge' });
  await shot(alice, '/submissions/' + data.ceId, 'ui_submission_compile_error.png', { waitFor: '.badge' });
  await shot(alice, '/submissions/' + data.tleId, 'ui_submission_tle.png', { waitFor: '.badge' });

  // Admin pages: crop each to the top, through the first test case, so the dense
  // form stays readable and fits on one page.
  await shot(admin, '/admin/new-problem', 'ui_admin_new_problem.png',
    { vp: { width: 760, height: 2400 }, waitFor: 'text=Test case 1', clipAbove: 'Test case 2' });
  await shot(admin, '/admin/edit-problem/' + data.editProblemId, 'ui_admin_edit_problem.png',
    { vp: { width: 760, height: 2400 }, waitFor: 'text=Test case 1', clipAbove: 'Test case 2' });

  await browser.close();
  console.log('all screenshots done');
}

main().catch((e) => { console.error(e); process.exit(1); });
