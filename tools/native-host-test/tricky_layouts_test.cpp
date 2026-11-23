// Calls the app's REAL editLine exactly like Kotlin does: expected text = the app's own lineText().
#include "EditingDocument.h"
#include "TextMutation.h"
#include "Save.h"
#include <fpdfview.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <cstdio>
#include <cstdlib>
static std::u16string u16(const char* s){std::u16string o;for(;*s;++s)o.push_back((unsigned char)*s);return o;}
static std::string u8(const std::u16string& s){std::string o;for(char16_t c:s){ if(c<0x80)o.push_back((char)c); else {char b[16];snprintf(b,16,"<U+%04X>",(unsigned)c);o+=b;} }return o;}
int main(int argc,char**argv){
    FPDF_InitLibrary();
    float l=atof(argv[2]),b=atof(argv[3]),r=atof(argv[4]),t=atof(argv[5]);
    int fd=open(argv[1],O_RDONLY); struct stat st; fstat(fd,&st);
    EditingDocument doc(fd,0,st.st_size);
    auto pieces=TextMutation::linePieces(&doc,0,l,b,r,t);
    std::u16string expected=TextMutation::lineText(pieces);
    printf("pieces=%zu expected='%s'\n",pieces.size(),u8(expected).c_str());
    float m[6]={1,0,0,1,0,0};
    int res=TextMutation::editLine(&doc,0,l,b,r,t,expected,u16(argv[6]),nullptr,0.f,0,nullptr,false);
    printf("editLine=%d\n",res);
    if(res==0) printf("saved=%d\n",Save::saveAsCopy(&doc,argv[7]));
    return res;
}
