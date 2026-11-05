import { useState } from 'react';
import { Check, ShieldCheck } from 'lucide-react';
import { post } from './api';
import type { Profile } from './types';
import { Modal, SaveButton } from './ui';

export function ProfileImport({ profiles, onClose, onSaved }: { profiles: Profile[]; onClose: () => void; onSaved: () => Promise<void> }) {
  const [selected, setSelected] = useState(profiles.map((_, i) => i));
  const [imported, setImported] = useState<number[]>([]);
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  return <Modal title="检查并导入职责模板" description={`文件包含 ${profiles.length} 个模板。导入将创建本地新模板，原有版本保持不变。`} onClose={onClose}><form onSubmit={async event => {
    event.preventDefault(); setError(''); setBusy(true);
    const completed = [...imported];
    try {
      const pending = selected.filter(index => !completed.includes(index));
      if (!pending.length) throw new Error('请选择尚未导入的模板');
      for (const index of pending) {
        const profile = profiles[index];
        await post('/profiles', { name: profile.name, role: profile.role, instructions: profile.instructions, capabilities: profile.capabilities || [] });
        completed.push(index); setImported([...completed]);
      }
      await onSaved(); onClose();
    } catch (e) { setError(`${completed.length ? `已导入 ${completed.length} 项，剩余项目未完成。` : ''}${(e as Error).message}`); await onSaved(); }
    finally { setBusy(false); }
  }}><div className="form-body"><div className="info-box"><ShieldCheck size={18} /><div><b>职责声明不会扩大任务权限</b><p>每个模板仍需通过核心的能力校验，运行中的任务继续使用原版本。</p></div></div>{profiles.map((profile, index) => <div className="profile-import-row" key={index}><label><input type="checkbox" disabled={busy || imported.includes(index)} checked={selected.includes(index)} onChange={event => setSelected(old => event.target.checked ? [...old, index] : old.filter(i => i !== index))} /><span><b>{profile.name}</b><small>{profile.role} · {(profile.capabilities || []).join('、') || '无额外能力'}</small></span>{imported.includes(index) && <Check size={15} />}</label><details><summary>查看职责内容</summary><p>{profile.instructions}</p></details></div>)}{error && <div className="form-error" role="alert">{error}</div>}</div><footer><button className="button secondary" type="button" onClick={onClose} disabled={busy}>取消</button><SaveButton busy={busy}>导入选中的 {selected.filter(index => !imported.includes(index)).length} 个模板</SaveButton></footer></form></Modal>;
}
