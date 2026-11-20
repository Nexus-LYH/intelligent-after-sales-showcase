import { captureRequestScope, type AuthSource } from './request-scope'
/** Local display lease. Every server request still needs current authorization. */
export class ObjectLease<T> {
  value:T|null=null; busy=false; error=''; expiresAt=0
  private epoch=0; private alive=true
  constructor(private source:AuthSource,private context:()=>string,private read:()=>Promise<T>,private clock=()=>Date.now(),private ttl=30000,private onFailure:(e:unknown)=>void=()=>{}){}
  clear(message=''){this.epoch++;this.value=null;this.busy=false;this.expiresAt=0;this.error=message}
  expire(){if(this.expiresAt&&this.clock()>=this.expiresAt)this.clear('当前对象权限核验已过期，请重新查询。')}
  async refresh(){
    if(!this.alive||this.busy)return
    const slot=++this.epoch,key=this.context(),scope=captureRequestScope(this.source,()=>this.alive&&slot===this.epoch&&key===this.context());this.busy=true
    try{const value=await this.read();if(!scope.current())return;this.value=value;this.expiresAt=this.clock()+this.ttl;this.error=''}
    catch(e){if(scope.current()){this.clear(e instanceof Error?e.message:'当前对象权限无法确认');this.onFailure(e)}}
    finally{if(scope.current())this.busy=false}
  }
  dispose(){this.alive=false;this.clear()}
}
