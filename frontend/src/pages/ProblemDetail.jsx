import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import Editor from '@monaco-editor/react';
import client from '../api/client';
import { useAuth } from '../context/AuthContext';

const LANG_DEFAULTS = {
  CPP: `#include <iostream>\nusing namespace std;\n\nint main() {\n    // your solution\n    return 0;\n}\n`,
  PYTHON: `# your solution\n`,
  JAVA: `public class Main {\n    public static void main(String[] args) {\n        // your solution\n    }\n}\n`,
};

const MONACO_LANG = { CPP: 'cpp', PYTHON: 'python', JAVA: 'java' };

export default function ProblemDetail() {
  const { slug } = useParams();
  const [problem, setProblem] = useState(null);
  const [language, setLanguage] = useState('CPP');
  const [code, setCode] = useState(LANG_DEFAULTS.CPP);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const { user } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    client.get(`/problems/${slug}`).then(res => setProblem(res.data));
  }, [slug]);

  const changeLanguage = (lang) => {
    setLanguage(lang);
    setCode(LANG_DEFAULTS[lang]);
  };

  const submit = async () => {
    if (!user) { navigate('/login'); return; }
    setSubmitting(true);
    setError('');
    try {
      const { data } = await client.post('/submissions', {
        problemId: problem.id, language, sourceCode: code,
      });
      navigate(`/submissions/${data.id}`);
    } catch (err) {
      setError(err.response?.data?.error || 'Submission failed');
    } finally {
      setSubmitting(false);
    }
  };

  const deleteProblem = async () => {
    if (!confirm(`Delete "${problem.title}"? This cannot be undone.`)) return;
    try {
      await client.delete(`/problems/${problem.id}`);
      navigate('/');
    } catch (err) {
      setError(err.response?.data?.error || 'Failed to delete problem');
    }
  };

  if (!problem) return <p>Loading...</p>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>{problem.title} <span className={`badge ${problem.difficulty}`}>{problem.difficulty}</span></h2>
        {user?.role === 'ADMIN' && (
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button className="secondary" onClick={() => navigate(`/admin/edit-problem/${problem.id}`)}>Edit</button>
            <button className="secondary" style={{ color: '#f08a8a' }} onClick={deleteProblem}>Delete</button>
          </div>
        )}
      </div>
      <div className="card">
        <p style={{ whiteSpace: 'pre-wrap' }}>{problem.statement}</p>
        {problem.constraints && <><h4>Constraints</h4><p style={{ whiteSpace: 'pre-wrap' }}>{problem.constraints}</p></>}
        <h4>Sample tests</h4>
        {problem.publicTestCases?.map((tc, i) => (
          <div key={i} style={{ display: 'flex', gap: '1rem', marginBottom: '0.5rem' }}>
            <pre style={{ background: '#0f1115', padding: '0.5rem', flex: 1 }}>Input:{"\n"}{tc.input}</pre>
            <pre style={{ background: '#0f1115', padding: '0.5rem', flex: 1 }}>Output:{"\n"}{tc.expectedOutput}</pre>
          </div>
        ))}
      </div>

      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
          <select value={language} onChange={e => changeLanguage(e.target.value)}>
            <option value="CPP">C++</option>
            <option value="PYTHON">Python</option>
            <option value="JAVA">Java</option>
          </select>
          <button onClick={submit} disabled={submitting}>{submitting ? 'Submitting...' : 'Submit'}</button>
        </div>
        <Editor
          height="400px"
          language={MONACO_LANG[language]}
          theme="vs-dark"
          value={code}
          onChange={(v) => setCode(v ?? '')}
        />
        {error && <div style={{ color: '#f08a8a', marginTop: '0.5rem' }}>{error}</div>}
      </div>
    </div>
  );
}
