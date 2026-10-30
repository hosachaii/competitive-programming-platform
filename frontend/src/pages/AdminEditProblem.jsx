import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import client from '../api/client';

export default function AdminEditProblem() {
  const { id } = useParams();
  const navigate = useNavigate();

  const [loaded, setLoaded] = useState(false);
  const [slug, setSlug] = useState('');
  const [title, setTitle] = useState('');
  const [difficulty, setDifficulty] = useState('EASY');
  const [statement, setStatement] = useState('');
  const [constraints, setConstraints] = useState('');
  const [timeLimitMs, setTimeLimitMs] = useState(2000);
  const [memoryLimitMb, setMemoryLimitMb] = useState(256);
  const [tags, setTags] = useState('');
  const [testCases, setTestCases] = useState([]);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    client.get(`/problems/${id}/admin`).then(res => {
      const p = res.data;
      setSlug(p.slug);
      setTitle(p.title);
      setDifficulty(p.difficulty);
      setStatement(p.statement);
      setConstraints(p.constraints || '');
      setTimeLimitMs(p.timeLimitMs);
      setMemoryLimitMb(p.memoryLimitMb);
      setTags((p.tags || []).join(', '));
      setTestCases(p.testCases.map(tc => ({
        input: tc.input, expectedOutput: tc.expectedOutput, isPublic: tc.isPublic, weight: tc.weight,
      })));
      setLoaded(true);
    }).catch(err => setError(err.response?.data?.error || 'Failed to load problem'));
  }, [id]);

  const updateTestCase = (i, field, value) => {
    setTestCases(tcs => tcs.map((tc, idx) => idx === i ? { ...tc, [field]: value } : tc));
  };
  const addTestCase = () => setTestCases(tcs => [...tcs, { input: '', expectedOutput: '', isPublic: false, weight: 1 }]);
  const removeTestCase = (i) => setTestCases(tcs => tcs.filter((_, idx) => idx !== i));

  const submit = async (e) => {
    e.preventDefault();
    setError('');

    if (testCases.length === 0) {
      setError('At least one test case is required.');
      return;
    }
    if (!testCases.some(tc => tc.isPublic)) {
      setError('At least one test case should be marked public.');
      return;
    }

    setSubmitting(true);
    try {
      await client.put(`/problems/${id}`, {
        title, difficulty, statement,
        constraints: constraints || null,
        timeLimitMs: Number(timeLimitMs),
        memoryLimitMb: Number(memoryLimitMb),
        tags: tags.split(',').map(t => t.trim()).filter(Boolean),
        testCases: testCases.map(tc => ({
          input: tc.input, expectedOutput: tc.expectedOutput,
          isPublic: tc.isPublic, weight: Number(tc.weight) || 1,
        })),
      });
      navigate(`/problems/${slug}`);
    } catch (err) {
      setError(err.response?.data?.error || JSON.stringify(err.response?.data) || 'Failed to update problem');
    } finally {
      setSubmitting(false);
    }
  };

  if (!loaded && !error) return <p>Loading...</p>;

  const textareaStyle = { width: '100%', background: '#0f1115', color: '#e6e6e6', border: '1px solid #30333a', borderRadius: 6, padding: '0.5rem' };

  return (
    <div>
      <h2>Edit Problem</h2>
      {error && !loaded && <div style={{ color: '#f08a8a' }}>{error}</div>}
      {loaded && (
        <form onSubmit={submit} style={{ maxWidth: 700 }}>
          <label>Title</label>
          <input value={title} onChange={e => setTitle(e.target.value)} required />

          <label>Difficulty</label>
          <select value={difficulty} onChange={e => setDifficulty(e.target.value)}>
            <option value="EASY">Easy</option>
            <option value="MEDIUM">Medium</option>
            <option value="HARD">Hard</option>
          </select>

          <label>Statement</label>
          <textarea rows={6} value={statement} onChange={e => setStatement(e.target.value)} required style={textareaStyle} />

          <label>Constraints (optional)</label>
          <textarea rows={3} value={constraints} onChange={e => setConstraints(e.target.value)} style={textareaStyle} />

          <div style={{ display: 'flex', gap: '1rem' }}>
            <div style={{ flex: 1 }}>
              <label>Time limit (ms)</label>
              <input type="number" value={timeLimitMs} onChange={e => setTimeLimitMs(e.target.value)} />
            </div>
            <div style={{ flex: 1 }}>
              <label>Memory limit (MB)</label>
              <input type="number" value={memoryLimitMb} onChange={e => setMemoryLimitMb(e.target.value)} />
            </div>
          </div>

          <label>Tags (comma-separated)</label>
          <input value={tags} onChange={e => setTags(e.target.value)} />

          <h3>Test cases</h3>
          {testCases.map((tc, i) => (
            <div key={i} className="card" style={{ padding: '1rem' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <strong>Test case {i + 1}</strong>
                <div>
                  <label style={{ marginRight: '0.5rem' }}>
                    <input type="checkbox" checked={tc.isPublic}
                      onChange={e => updateTestCase(i, 'isPublic', e.target.checked)} /> Public (sample)
                  </label>
                  {testCases.length > 1 && (
                    <button type="button" className="secondary" onClick={() => removeTestCase(i)}>Remove</button>
                  )}
                </div>
              </div>
              <label>Input</label>
              <textarea rows={3} value={tc.input} onChange={e => updateTestCase(i, 'input', e.target.value)} required style={textareaStyle} />
              <label>Expected output</label>
              <textarea rows={3} value={tc.expectedOutput} onChange={e => updateTestCase(i, 'expectedOutput', e.target.value)} required style={textareaStyle} />
            </div>
          ))}
          <button type="button" className="secondary" onClick={addTestCase}>+ Add test case</button>

          {error && <div style={{ color: '#f08a8a' }}>{error}</div>}
          <button type="submit" disabled={submitting}>{submitting ? 'Saving...' : 'Save changes'}</button>
        </form>
      )}
    </div>
  );
}
