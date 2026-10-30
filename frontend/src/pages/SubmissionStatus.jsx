import { useEffect, useState, useRef } from 'react';
import { useParams } from 'react-router-dom';
import client from '../api/client';

const TERMINAL = ['ACCEPTED', 'WRONG_ANSWER', 'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED',
                   'RUNTIME_ERROR', 'COMPILE_ERROR', 'INTERNAL_ERROR'];

export default function SubmissionStatus() {
  const { id } = useParams();
  const [submission, setSubmission] = useState(null);
  const [error, setError] = useState('');
  const intervalRef = useRef(null);

  useEffect(() => {
    const poll = () => {
      client.get(`/submissions/${id}`).then(res => {
        setSubmission(res.data);
        setError('');
        if (TERMINAL.includes(res.data.status) && intervalRef.current) {
          clearInterval(intervalRef.current);
        }
      }).catch(err => {
        setError(err.response?.data?.error || 'Failed to load submission status');
        if (intervalRef.current) clearInterval(intervalRef.current);
      });
    };
    poll();
    intervalRef.current = setInterval(poll, 1500);
    return () => clearInterval(intervalRef.current);
  }, [id]);

  if (error) return <p style={{ color: '#f08a8a' }}>{error}</p>;
  if (!submission) return <p>Loading...</p>;

  return (
    <div>
      <h2>Submission #{submission.id} <span className={`badge ${submission.status}`}>{submission.status}</span></h2>
      <div className="card">
        <p>Score: {submission.score}%</p>
        {submission.runtimeMs != null && <p>Runtime: {submission.runtimeMs} ms</p>}
        {submission.errorMessage && <pre style={{ background: '#0f1115', padding: '0.5rem', color: '#f08a8a' }}>{submission.errorMessage}</pre>}
      </div>
      {submission.results?.length > 0 && (
        <div className="card">
          <h4>Test results</h4>
          <table>
            <thead><tr><th>Test</th><th>Verdict</th><th>Runtime</th></tr></thead>
            <tbody>
              {submission.results.map((r, i) => (
                <tr key={r.testCaseId}>
                  <td>{r.isPublic ? `Sample ${i + 1}` : `Hidden ${i + 1}`}</td>
                  <td><span className={`badge ${r.verdict}`}>{r.verdict}</span></td>
                  <td>{r.runtimeMs} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
