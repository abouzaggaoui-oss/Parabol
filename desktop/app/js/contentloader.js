/* Parabol Desktop — what to download, per content type (same rules as Android's ContentLoader).
 * - Groups already on disk are kept; only visible groups that are missing are fetched
 *   (force: all visible groups again, for "Vernieuwen").
 * - Few groups to fetch: one request per group (category_id), a few at a time.
 *   Many groups, or nothing stored yet: one request for everything.
 * - A provider that ignores category_id answers with everything; that answer is used as is. */

const ContentLoader = {
  BULK_THRESHOLD: 25,
  PARALLEL: 6,

  /** fetchAll() / fetchGroup(id) resolve to an array, or null on failure. */
  async refresh({ categories, cached, isHidden, force, categoryOf, fetchAll, fetchGroup }) {
    const known = new Set(categories.map((c) => String(c.category_id)));
    const kept = {};
    Object.keys(cached || {}).forEach((id) => { if (!categories.length || known.has(id)) kept[id] = cached[id]; });
    const pruned = Object.keys(kept).length !== Object.keys(cached || {}).length;
    const visible = [...known].filter((id) => !isHidden(id));
    const needed = force ? visible : visible.filter((id) => !(id in kept));
    if (!needed.length) return { groups: kept, failed: false, changed: pruned };

    const groupAll = (items) => {
      const grouped = {};
      items.forEach((x) => { const id = String(categoryOf(x)); (grouped[id] = grouped[id] || []).push(x); });
      const result = { ...kept };
      known.forEach((id) => { result[id] = grouped[id] || []; });
      Object.keys(grouped).forEach((id) => { if (!known.has(id)) result[id] = grouped[id]; });
      return result;
    };

    if (!Object.keys(kept).length || needed.length > this.BULK_THRESHOLD) {
      const all = await fetchAll();
      if (!all) return { groups: kept, failed: true, changed: pruned };
      return { groups: groupAll(all), failed: false, changed: true };
    }

    const result = { ...kept };
    let failed = false;
    let fetched = false;
    const first = needed[0];
    const firstItems = await fetchGroup(first);
    if (!firstItems) failed = true;
    else if (firstItems.some((x) => String(categoryOf(x)) !== first)) return { groups: groupAll(firstItems), failed: false, changed: true };
    else { result[first] = firstItems; fetched = true; }

    const rest = needed.slice(1);
    let next = 0;
    const worker = async () => {
      while (next < rest.length) {
        const id = rest[next++];
        const items = await fetchGroup(id);
        if (!items) failed = true;
        else { result[id] = items.filter((x) => String(categoryOf(x)) === id); fetched = true; }
      }
    };
    await Promise.all(Array.from({ length: Math.min(this.PARALLEL, rest.length) }, worker));
    return { groups: result, failed, changed: fetched || pruned };
  },
};

if (typeof module !== 'undefined') module.exports = ContentLoader;
